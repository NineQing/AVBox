# 审查报告 review-20261008-batch1

- 范围：**未提交改动**（`git diff`，26 文件 / +464 −25），非全库
- 模式：快速模式（只报阻断与高）→ 实际有中级发现，故按深度模式格式出全表
- 被审对象：2026-10-08 播放器 Surface 竞态与切页链路修复批次（含其修复轮）
- 工具：`git diff` / `git status` / 语义检索（Grep）/ 括号配平脚本（方法行数估算）/ 真机落盘日志（`filesDir/preload_debug.log`）
- 结论：**未达收尾终止线**（仍有 4 条中级）

---

## 0. 覆盖度声明

- 本批文件数 26：已审 24、跳过 2（`skill/history/features.md`、`skill/avbox-playback-service-spec.md` —— 文档，按排除范围不计发现）
- 已审 app 源码 22 文件、测试 1 文件（`PlayerUiStateVisibilityTest.kt`）
- 锚点类：`PlayerEngine` ✓ / `AppPlayerView` ✓ / `EngineSurfaceRenderView` ✓ / `EngineTextureRenderView` ✓ / `ComposeVideoController` ✓ / `PlayContainer` ✓ / `PlaybackEngine` ✓ / `PlaybackController` ✓ / `KernelPlayer`+`ExoPlayer` ✓ / `EngineTrackSelection`（只读，查双写）✓
- 未展开：`quickjs/`、`pyramid/`、`libs/`（排除范围）；全库其余文件未审（本批为 diff 审查）
- 真机证据来源：`.logs/live/preload_surface_only.log`（11:12:53 起，SurfaceView 配置）、`.logs/live/preload_progress_again.log`（11:12:53 起）

## 1. 项目架构评价

本批改动集中在播放器「输出面（Surface）生命周期 × 渲染器启用态 × 页面挂载」这条链上，架构未变：仍由 `PlaybackEngine` 持有唯一 media3 内核、页面只挂摘容器（D 系列决策不变）。

值得注意的结构特征（不构成本批缺陷）：输出面的控制权被分散在 4 个位置 —— `PlayerEngine`（解绑/启用态）、`AppPlayerView`（挂摘容器 + 换渲染视图）、两个 `Engine*RenderView`（各自的 surface 回调）、`MyVideoView`（对齐配置）。本批新增的 `detachVideoSurface()` 正是在给这条链补第三种"解绑"语义（只解绑、不改启用态），因此**语义一致性**成为本批的主要风险面（见 #1、#2）。

## 2. 值得保留的设计

- `PlayerEngine.applyRendererEnablement()` 把「视频渲染器启用 ⟺ `!audioOnlyRequested && !videoOutputInvalid`」收敛成唯一判据，并用 `videoRenderersDisabled` 做"变更才下发"，取代原先散落在各调用方的手动门控 —— 方向正确，建议保持。
- `EngineSurfaceRenderView` 的 `released` 守卫：挡住了"被移除的旧视图迟到回调清掉新输出"这类异步竞态，是本次修得最干净的一处。
- `PlayerUiState.exitPaused` 与既有 `lifecyclePaused` 分离（不复用），保留了"退后台任务快照"语义 —— 分离而非合并是对的。

## 3. 问题清单

### #1 `KernelPlayer.detachVideoSurface()` 默认实现把语义反转

- 位置：`app/src/main/java/com/github/tvbox/osc/player/KernelPlayer.kt:85`（锚点 `open fun detachVideoSurface() {`）
- 描述：默认实现走 `setSurface(null)`，即经 `ExoPlayer.setSurface` → `PlayerEngine.setVideoSurface(null)` → 置 `videoOutputInvalid = true` → **禁用**视频渲染器，与该方法契约（"只解绑、不改启用态"）**相反**。当前唯一内核 `ExoPlayer` 已覆写，故无现网后果；但新增内核若漏覆写，会静默得到"切页触发 track 重配"的相反行为。
- 严重度：中 ｜ 引入维度：本次引入
- 证据：

```kotlin
open fun clearDisplay() { setSurface(null) }
open fun detachVideoSurface() { setSurface(null) }   // 与 clearDisplay 同实现，语义却是"不切启用态"
```

- 建议：默认实现改为 `= Unit`（或声明 `abstract`），让"不改启用态"成为默认语义。

### #2 `surfaceDestroyed` 与切页解绑语义分裂，且依赖未文档化的框架行为

- 位置：`app/src/main/java/com/github/tvbox/osc/player/host/EngineSurfaceRenderView.kt:103`（锚点 `mediaPlayer?.clearDisplay()`）vs `app/src/main/java/com/github/tvbox/osc/player/AppPlayerView.kt:569`（锚点 `mMediaPlayer?.detachVideoSurface()`）
- 描述：同一条"换 Surface"链上两种语义：挂摘容器用 `detachVideoSurface()`（不切启用态），`surfaceDestroyed` 用 `clearDisplay()`（置 invalid → 禁用渲染器 → media period 重配 + 约 460ms 重缓冲）。实测本次 4 次切页 `echo-surface: created valid=true` ×4、`echo-surface: destroyed` **0 次**，即容器重挂时 `surfaceDestroyed` 未触发，所以当前没有 toggle；但这是**未文档化的框架行为**，某机型/版本一旦触发即重缓冲回归。
- 严重度：中 ｜ 引入维度：既有被放大（`clearDisplay()` 为既有；`detachVideoSurface()` 为本次新增，两者并存才产生分裂）
- 证据：

```
11:13:08.099  echo-exo-detach-surface: renderers kept
11:13:08.229  echo-surface: created valid=true
（全文 echo-surface: destroyed 计数 = 0）
```

- 建议：`surfaceDestroyed` 也改走 `detachVideoSurface()`；仅当确实要"停止解码"时才用 `clearDisplay()`。

### #3 `startSession` 无条件清 `audioOnlyConfirmed`，与注释所述场景不匹配

- 位置：`app/src/main/java/com/github/tvbox/osc/player/PlaybackController.kt:60`（锚点 `st.audioOnlyConfirmed = false`）
- 描述：原实现只在**内容变化**时清（在 `if (contentChanged)` 块内），本次移到块外**每次 `startSession` 都清**。注释给的场景是"先播纯音频、再播带视频轨的内容"，而该场景在原实现里**已经会清**；真正被改变的只有"同 `playbackKey` 重新 startSession"这一支。消费方 4 处：`MusicSessionDelegate:80 isConfirmedAudioOnly()`（被 `PlayContainer.alignInstanceConfigOnTakeover` 的 renderChanged、`DetailActivity.isAudioContent()` 的自动进音乐页、`PlaybackEngine.onPlayStateChanged` 的遮黑/揭面、`MusicSessionDelegate:217` 起播后重试使用）。窗口内这 4 处会看到 `false`。**是否有 UI 路径落在窗口内：不确定**（本批日志里 12 条 gate 全为 `audioOnlyConfirmed=false`、`auto-watch matched=true` 0 次，未覆盖纯音频内容）。
- 严重度：中 ｜ 引入维度：本次引入
- 证据：

```kotlin
if (currentSession == null || !TextUtils.equals(currentSession!!.playbackKey(), session.playbackKey())) {
    music.clearArtworks()
    // 原位置：st.audioOnlyConfirmed = false
}
st.audioOnlyConfirmed = false      // 本次移到此处 ⇒ 每次 startSession 都清
```

- 建议：改回"内容变化才清"，或让该标记由 `playbackKey` 派生（消除手动清理）。

### #4 `applyRendererEnablement` 与 `EngineTrackSelection` 双写同一 `rendererDisabled` 参数

- 位置：`app/src/main/java/com/github/tvbox/osc/player/engine/PlayerEngine.kt:517`（锚点 `if (disable == videoRenderersDisabled) return`）vs `app/src/main/java/com/github/tvbox/osc/player/engine/EngineTrackSelection.kt:165`（锚点 `builder.setRendererDisabled(rendererIndex, false)`）
- 描述：`videoRenderersDisabled` 只由 `applyRendererEnablement` 维护，而用户选轨（`echo-setTrack`）也会写同一参数。两者不同步时，"变更才下发"会早退，实际启用态与记录不一致。方向性后果：只会出现"记录已禁用、实际已启用"（音乐页仍解视频 = 浪费解码），**不会黑屏**（无第三方下发 `true`）。
- 严重度：低 ｜ 引入维度：本次引入
- 证据：

```kotlin
builder.setRendererDisabled(index, disable)      // PlayerEngine.applyRendererEnablement
builder.setRendererDisabled(rendererIndex, false) // EngineTrackSelection.setTrack
```

- 建议：选轨后回调引擎重算，或 `applyRendererEnablement` 不做早退、每次下发。

### #5 文件级可变状态 `lastDrawnProgress` 服务于诊断日志

- 位置：`app/src/main/java/com/github/tvbox/osc/player/ui/PlayerBottomBar.kt:52`（锚点 `private var lastDrawnProgress = Float.NaN`）
- 描述：顶层文件级 `var`，跨 Composable / 播放器实例共享；唯一用途是"避免重复打印 `echo-bar-draw`"。不触发重组（非快照状态），故无功能后果，但违反"避免共享可变状态"。
- 严重度：低 ｜ 引入维度：本次引入
- 建议：排查结束后删除该字段与对应日志。

### #6 热路径诊断日志常开（release 也走 logcat）

- 位置：`app/src/main/java/com/github/tvbox/osc/player/controller/ComposeVideoController.kt:346`（锚点 `val staleContent = contentUrl == null ||`）之前的 `echo-progress-tick`；`app/src/main/java/com/github/tvbox/osc/ui/activity/DetailActivity.kt:255` `echo-music auto-watch`、`:270` `echo-music audio-content`
- 描述：`LOG.i` 恒走 `Log.i`（只有文件日志受 `BuildConfig.DEBUG` 限制），故 release 包也持续输出。实测频率：`auto-watch`/`audio-content` 约 0.3s 各一条、`echo-progress-tick` 每秒一条；单次会话日志文件已达 1.8MB。
- 严重度：低（release 仅 logcat 噪声，debug 有磁盘/IO 开销）｜ 引入维度：本次引入（诊断日志）
- 建议：收尾时把纯诊断日志降级 `LOG.d` 或移除。

### #7 `release()` 未清空 `videoRenderers` / `videoRendererIndices`

- 位置：`app/src/main/java/com/github/tvbox/osc/player/engine/PlayerEngine.kt:372`（锚点 `audioOnlyRequested = false`）附近；写入方 `app/src/main/java/com/github/tvbox/osc/player/engine/EngineRenderersFactory.kt:76`（锚点 `videoRendererIndices.add(i)`）
- 描述：`release()` 复位三个布尔但不清两个列表；`buildVideoRenderers` 每次调用都 `add`。当前 `createPlayer()` 每引擎只调一次，**不可达**；一旦同引擎重建播放器，索引与渲染器会重复累积（`notifyVideoOutputResolution`、`disableFrameRateMatching` 会对同一 renderer 重复发消息）。
- 严重度：低 ｜ 引入维度：本次引入（`videoRendererIndices`）
- 建议：`release()` 里一并 `clear()` 两个列表。

### #8 `detachVideoSurface()` 使播放器在无输出面时继续解码

- 位置：`app/src/main/java/com/github/tvbox/osc/player/engine/PlayerEngine.kt:410`（锚点 `fun detachVideoSurface() {`）
- 描述：只 `clearVideoSurface()`、不置 `videoOutputInvalid` ⇒ 换面期间渲染器保持启用，解码器在 null surface 上继续解码。这是为"换面不重缓冲"付出的刻意代价（功耗/浪费），非缺陷，但 `skill/avbox-playback-service-spec.md` §3.8 只记了收益未记代价。
- 严重度：低 ｜ 引入维度：本次引入
- 建议：在 spec §3.8 该条补一句代价。

### #9 接管分支播种的地址缺少一致性校验（**不确定**）

- 位置：`app/src/main/java/com/github/tvbox/osc/ui/player/PlayContainer.kt:461`（锚点 `mController.onContentUrlSet(mVideoView?.currentUrl)`）
- 描述：播种的是**播放器当前地址**，前提是"播放器装的确实是 `session.playbackKey()` 那个内容"。`isSamePlaybackOwned` 比对的是业务侧 `scheduler.startedPlaybackKey()`，与播放器实际 `mUrl` 无强一致保证；两者在切换途中不同步即会重现"进度条闪回上一部"。本批已因同类思路踩过一次坑（`setKernelProvider` 播种），故列为**不确定**风险项。
- 严重度：中（不确定）｜ 引入维度：本次引入
- 证据：

```kotlin
ownedPlaybackKey = session.playbackKey()
if (mController != null) mController.onContentUrlSet(mVideoView?.currentUrl)
```

- 建议：播种前加一致性校验（`mVideoView.currentUrl == scheduler.webPlayUrl()`），不一致则不播。

## 4. 重构决策

**A：不需要重构，继续开发。**

理由：本批是局部缺陷修复集合，未触碰架构、未新增模块、未改分层；剩余问题（#1/#4/#5/#6/#7）均为单点语义/清理项，改一个类即可，不构成"小范围重构"（B）的量级。代码成熟度方面，播放链路的挂摘协议与内核复用机制是稳定既有资产，本次只在其上补了第三种解绑语义。

## 5. 优先级

- **P0**：无
- **P1**：#3（`audioOnlyConfirmed` 清理条件）、#2（`surfaceDestroyed` 语义统一）、#9（播种一致性校验，先加断言式校验）
- **P2**：#1、#4、#5、#6、#7、#8

## 6. 渐进式重构计划

不适用（决策 A）。

## 附录 A. 度量盘点

口径：文件行数 = `wc -l`（本批 = `git diff --name-only` 命中的 `app/src/main` 文件）；方法行数 = 括号配平脚本**估算**（多行签名会漏检，标 ±）；门面依赖 = `grep -rl` 命中文件数（全库 `app/src/main`）。

| 指标 | 命中数 | 明细（Top N，相对路径:行号） | 统计口径 |
| --- | --- | --- | --- |
| 文件 > 500 行 | 本批 **7** | `player/PlaybackController.kt:1` 894、`player/engine/PlayerEngine.kt:1` 800、`ui/player/PlayContainer.kt:1` 757、`player/PlaybackEngine.kt:1` 638、`player/AppPlayerView.kt:1` 627、`ui/activity/MusicPlayerActivity.kt:1` 582、`player/controller/ComposeVideoController.kt:1` 575 | `wc -l`（本批 22 个 app 源码文件） |
| 方法 > 100 行 | 本批 **0** | — | 括号配平脚本估算，**±** 未逐条人工核对；多行签名与字符串内花括号会漂移，故 0 不等于"确认无超长方法" |
| 同文件重复模板 ≥3 次 | 本批 **2** | `player/controller/ComposeVideoController.kt` `getOrDefault` ×4、`ui/player/PlayContainer.kt` `if (mController != null)` ×9 | `grep -c`（同文件内同一模式出现次数） |
| 门面被依赖 ≥20 文件 | **3** | `util/LOG.kt` 129、`util/kv`（`KV.get(`）62、`api/ApiConfig.kt` 38 | `grep -rl '<模式>' app/src/main \| wc -l` |

对照（全库 Top 5 文件行数）：`api/ApiConfig.kt` 992、`ui/activity/DetailViewModel.kt` 989、`util/M3u8.kt` 931、`player/PlaybackController.kt` 894、`ui/music/MusicPlayerScreen.kt` 886。本批 7 个 >500 文件全部属**既有**规模，本批改动未使其显著增长（最大增量 `PlayerEngine.kt` +66 行）。

## 附录 B. 上轮对账（本批为同批自我更正轮）

| 上轮条目 | 状态 | 本轮新增证据 |
| --- | --- | --- |
| `contentUrl` 播种放在 `setKernelProvider`（致换片进度条闪回上一部） | **已修** | 真机 `11:19:58.243 echo-bar-draw: uiPosition=342611 uiDuration=2796920`（旧片时长）→ 播种已移到 `PlayContainer.setData` 的 `isSamePlaybackOwned` 分支（`PlayContainer.kt:461`）；同片接管 `11:21:02` 起 `uiPosition` 连续跟随 `rawPosition` |
| 切页路径 `clearDisplay()` 致 codec 重建 + 约 460ms 重缓冲 | **已修** | `detachVideoSurface()` 版 4 次切页：attach→PLAYING 7~8ms、`echo-exo-codec-init` 全程 1 次、`outputInvalid=true` 0 次、`obsolete surface` 0 次 |
| Texture 渲染下切页黑屏有声 | **已修** | `onSurfaceTextureAvailable` 复用分支改走 `refreshSurface()`；每次重挂后 `outputInvalid=false`（`11:05:21.622 / 27.457 / 32.866`） |
| 退出全屏闪中央 ▶ | **已修** | `PlayerUiStateVisibilityTest` 新增 3 例（10/10 通过）；`DetailPlaybackPolicyTest` 11/11 不动 |
| 既有被放大（本批新增） | 记 #2（`surfaceDestroyed` 的 `clearDisplay()` 与新增 `detachVideoSurface()` 语义分裂，toggle 是否发生取决于框架是否触发 `surfaceDestroyed`） | 见 #2 |

---

## 收尾判定

**未达终止线。** 依据 `skill/SKILL.md`「审查收敛」：可以收尾 = 连续一轮没有阻断 / 高 / 中级发现，且剩余发现全部属于"既有问题"或"口味差异"。本批无阻断、无高级，但有 **4 条中级**（#1、#2、#3、#9），故不满足终止条件。

建议动作：先处理 P1 三项（#3 需先确认纯音频内容的真机行为、#2 一行改动、#9 加校验），再跑一轮；P2 六项可作为收尾清理（删诊断日志 + 补默认实现语义 + 清列表）与提交前一次性处理。

---

## 修复轮 1（2026-10-08 11:31）

### 改了哪些条目

| 条目 | 处理 | 改动 |
| --- | --- | --- |
| #1 | **已修** | `player/KernelPlayer.kt:85`：`open fun detachVideoSurface() { setSurface(null) }` → `abstract fun detachVideoSurface()`（唯一子类 `ExoPlayer` 已实现，编译期强制，杜绝漏覆写静默反向） |
| #2 | **已修** | `player/host/EngineSurfaceRenderView.kt:103`：`mediaPlayer?.clearDisplay()` → `mediaPlayer?.detachVideoSurface()`；日志锚点 `echo-surface: destroyed -> clearDisplay` 改为 `echo-surface: destroyed -> detach`。`addDisplay()` 里的 `clearDisplay()` **保留**（音乐页退出路径的渲染器开关守卫仍靠它） |
| #3 | **已驳回（审查者误报）→ 已恢复 WIP 原样** | 我依据 `playbackKey()` 含 `vod.id` 推断"原条件已覆盖"，漏掉一条关键路径：**音乐页的 `setMusicAudioOnly(true)` 会强制 audio-only，`MusicSessionDelegate:201` 因而把 `audioOnlyConfirmed` 置 true —— 即使内容本身带视频轨**。于是"从音乐页返回同一内容"时 `playbackKey` 相同、原条件不清 ⇒ `isConfirmedAudioOnly()` 仍为 true ⇒ `DetailActivity.isAudioContent()` 判为音频 ⇒ 又被弹回音乐页（真机复现："进入视频就会重新进入音乐播放页"）。WIP 的"每次 `startSession` 都清"正是修这个的，已原样恢复 |
| #9 | **不改（留档）** | 见下方"回归面清单"：加 `scheduler.webPlayUrl()` 比对会在"线路地址 ≠ 播放器实际地址"时误判不一致 ⇒ 不播种 ⇒ 进度条卡 0 复发（正是 11:05 修掉的缺陷）。故维持现状并留档观察 |
| #4 / #5 / #6 / #7 / #8 | 未动（P2，提交前一次性处理） | — |

### 构建与单测结果

- `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` → `BUILD SUCCESSFUL`
- 单测：`PlayerUiStateVisibilityTest` 10/10、`DetailPlaybackPolicyTest` 11/11，0 失败
- 装机：vivo `10AF1J04JX0016G`（`lastUpdateTime` 见 features 条目）

### 回归面清单（不变量 × 消费方 × 结论）

| 改动 | 不变量 | 消费方 | 结论 |
| --- | --- | --- | --- |
| #1 `detachVideoSurface` 改 `abstract` | 内核必须显式声明"解绑不改启用态" | `KernelPlayer` 唯一子类 `ExoPlayer`（`grep ': KernelPlayer()'` 命中 1） | 安全；编译期即可暴露漏实现 |
| #2 `surfaceDestroyed` 改只解绑 | "输出面解绑" 与 "渲染器启用态" 解耦 | ①容器重挂（实测 `echo-surface: destroyed` 0 次，本就不触发）②`addDisplay()` 换渲染视图（`clearDisplay()` 保留，守卫不变）③`AppPlayerView.release()`（`mediaPlayer` 已先置 null，无副作用） | 语义统一；**需真机确认**音乐页往返 + 切页无 `DECODER_INIT_FAILED`、无重缓冲 |
| #3 恢复"内容变化才清" | `audioOnlyConfirmed` 绑定当前内容 | `MusicSessionDelegate:80 isConfirmedAudioOnly()` → `PlayContainer.alignInstanceConfigOnTakeover` 的 renderChanged、`DetailActivity.isAudioContent()` 自动进音乐页、`PlaybackEngine.onPlayStateChanged` 遮黑/揭面、`MusicSessionDelegate:217` 起播后重试 | **已驳回**：恢复原条件后真机复现"进入视频又被弹回音乐页"（音乐页强制 audio-only 会把带视频轨的内容也标成 `audioOnlyConfirmed=true`，同 `playbackKey` 返回时不被清）⇒ 已恢复 WIP 原样 |

### 区分

- **本次引入**：#1 的修复对象是本批自己引入的问题
- **既有被放大**：#2 的两套语义并存是"新增 `detachVideoSurface()` 之后才出现的分裂"，`clearDisplay()` 本身是既有实现
- **审查者误报（已驳回）**：#3 —— 静态推理（`playbackKey` 含 `vod.id`）不足以判定，忽略了"音乐页强制 audio-only 会把带视频轨的内容也标成 `audioOnlyConfirmed=true`"这条运行时路径。**教训**：`audioOnlyConfirmed` 这类"运行期确认标记"的清理条件，必须把"谁写的"（音乐页强制模式）一并纳入推理，不能只看 key 的构成。
- **未覆盖**：`PlayContainer.stopForContentSwitch()` 的 `pause()` 未走 `setExitPaused`，换源停播时理论上仍可能闪中央 ▶（既有行为，非本批引入，留档）
