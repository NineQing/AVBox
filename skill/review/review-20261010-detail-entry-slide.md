# 审查报告：详情页进播放过渡（默认竖屏 + 播放器滑入/滑出）

- 日期：2026-10-10
- 范围：`DetailViewModel` / `DetailScreen` / `DetailActivity` / `DetailPlaybackCommands` / `PlayContainer` / `ComposeVideoController`，及 `DetailPlaybackOrientationTest` 删除；对照活规范 §4.4 与 `skill/history/features.md` 同日两条
- 方法：两路只读子代理审查（① 状态机与生命周期/边界；② 方向链与删除面/测试覆盖）+ 主代理逐条复核与修复
- 前置：用户真机走查判定"效果还不错"

## 一、已修（本次引入的真回归 / 缺陷）

| # | 级别 | 问题 | 证据 | 修法 |
|---|---|---|---|---|
| 1 | 高（两路独立命中） | **全屏内换集重放滑入**：播放器底栏「选集」→ 面板点集 → `applyPlaybackEntry(Episode)` → `enterFullscreen()` 无"已在全屏"守卫 ⇒ 播放器被拽出屏外再滑回 300ms（改动前 `fullScreen.value = true` 幂等，属新引入） | `DetailViewModel.enterFullscreen()`、`DetailScreen` entering 分支 `snapTo(0f)` | `if (fullScreen.value \|\| enteringFullscreen.value) return` |
| 2 | 中 | **幻影全屏**：`onEntrySlideFinished()` 无状态校验，与"滑入中取消"同帧时把 `fullScreen` 置 true，停在"伪全屏 + 已停播" | `DetailViewModel.onEntrySlideFinished()` | `if (!enteringFullscreen.value \|\| exitingFullscreen.value) return` |
| 3 | 高 | **横屏退全屏露空底**：`rotating=true` ⇒ `fullBox=true` ⇒ 海报内容层整层不渲染，滑出只揭开模糊底、旋转落地后海报页 pop 出现 | `DetailScreen` 内容层/TopScrim 的 `if (!fullBox)`、`DetailViewModel` 退全屏置 `rotating` | 可见条件改 `!fullBox \|\| exitingFullscreen` |
| 4 | 高 | **滑出与方向切换并发致位移跳变**：`slideWidth` 实时读 `LocalConfiguration`，系统旋转落地时按新宽度重算 | `DetailScreen` offset lambda | 转场开始时快照宽度（`slideWidthDp`） |
| 5 | 中 | **退全屏未清尺寸 watch**：跨会话残留 listener/定时器；且 `if (videoSizeWatchArmed) return` 会静默跳过重新武装、10s 预算不刷新 | `DetailActivity.armVideoSizeWatch/applyFullscreen` | 退全屏清 armed/listener/定时器 |
| 6 | 低 | **退出入口无前置条件**：`onNewIntent` 在海报页也会置 `exitingFullscreen`（无效滑出 + 多一帧拦截层） | `DetailViewModel.onFullScreenToggleRequested(false, …)` | 加 `if (!fullScreen.value && !enteringFullscreen.value) return` |

## 二、驳回（审查误判）

- "滑入期 D-pad/键事件不被拦截层拦截" —— 本项目只面向手机用户、无遥控器输入，不适用。

## 三、登记不修（低 / 既有）

1. 滑入 300ms 内播放器仍是预览 chrome、落位帧才切全屏 chrome（用户走查已接受；系统栏同理）
2. 拦截层覆盖不到播放器原生 `AndroidView` 与选集面板（面板仅在全屏态打开，而全屏态已不再触发滑入 ⇒ 影响面收敛）；可真机再验
3. 后台暂停期间尺寸回调仍可下方向（语义仍正确）
4. 返回键三分支读"VM flag / Activity 字段 / VM flow"三个来源、存在 1 帧错判窗口（既有架构）
5. `DetailPlaybackFacts.portraitVideo` 在生产路径已成死值；`DetailPlaybackCommandsTest` 的 `requested=true` 用例锁的是不可达分支（新链无常驻单测）
6. 平板（≥600dp）落位后仍会被硬转横屏（沿袭旧实现，已在规范 §4.4 标注）
7. 删除的 `DetailPlaybackOrientationTest` 中"尺寸未就绪 ⇒ 保持竖屏"1 例无语义等价覆盖（现由 `armVideoSizeWatch` 早退分支承担）

## 四、验证

- `:app:assembleDebug` 绿；`:app:testDebugUnitTest` **797 例 / 0 失败**
- 行尾 LF；规范 / history 三份镜像一致
- 设备未在线、未装机（用户机上是走查前版本，修复后需重装复验）

## 五、结论

**可收尾**（修复后需重装复验）：无阻断遗留；本次引入的回归已全部修掉；登记项均为低风险或既有行为，不需要在收尾前处理。

建议复验点：
1. 全屏内换集（底栏「选集」→ 面板点集）不再闪烁重放滑入；
2. 横屏片源全屏后按返回：滑出过程中可见海报页，而非只有模糊底；
3. 横屏退全屏的滑出过程无位移跳变。

---

# 第二轮审查（2026-10-10，用户要求"再审查一遍"）

**方法**：两路新只读子代理（① 专审上一轮 6 处修复本身；② 专扫真机未验的旁路与盲区）+ 主代理逐条复核。上一轮修复**未经真机验证**，故其自身即审查对象。

## 一、已修（两路独立命中的 2 高 + 2 中 + 1 低）

| # | 级别 | 问题 | 根因 | 修法 |
|---|---|---|---|---|
| 7 | **高（两路命中；属上一轮修复引入的回归）** | **`entering && exiting` 双真自锁**：滑入中收到 `onNewIntent`（或滑出中点选集）→ 两 flag 同真 → `when` 里 entering 优先使 exiting 分支饿死、`onEntrySlideFinished` 又被守卫拦下且不清 entering ⇒ `slideAnim` 停在 1、拦截层常驻、`fullScreen=false`：播放器压住海报页且海报页全不可点，只能按返回键靠 `cancelEntrySlide()` 脱出 | 上一轮修复 1/2/6 的守卫组合缺"进入/退出互斥" | 三处收敛互斥：`onFullScreenToggleRequested(false)` 置 exiting 前清 entering 且 exiting 时幂等返回；`enterFullscreen()` 置 entering 前清 exiting；`onEntrySlideFinished()` 改为"先清 entering，若 exiting 则交给退出收尾"；`when` 改为 exiting 优先；进入分支去掉 `snapTo(0f)`（滑出中途再进入时从当前进度续滑，顺带与规范口径一致） |
| 8 | **高（两路命中）** | **≥600dp 设备横屏退全屏后内容层永久不渲染**：`rotating` 只能被 `onConfigurationChanged` 清除，平板 `orientationPolicyValue()` 为 `UNSPECIFIED`、退全屏不产生配置变更 ⇒ `rotating` 永真 ⇒ `fullBox=true` 且播放器已滑出屏外 ⇒ 只剩模糊底（手机因强制回竖屏自愈） | `fullBox` 依赖"只能靠配置变更清除"的标志 | 滑出结束调 `DetailActivity.settleRotationAfterExit()`（清 `rotating` + `syncFullBoxSideEffects`，幂等） |
| 9 | 中 | **拦截层挡不住播放器原生 View**：过渡 300ms 内点到播放器区域 = 直接触发手势（单击切控制栏、双击暂停、横滑 seek；预览态只挡竖直滑动与长按） | 拦截层是 Compose 兄弟节点，互操作 `AndroidView` 不在 Compose 命中测试内 | `PlayContainer.setTouchBlocked()` + 覆写 `onInterceptTouchEvent`；Screen 用 `LaunchedEffect(touchBlocked, container)` 跟随两 flag 同步（异常路径随 flag 回收，不漏解锁） |
| 10 | 中 | **静止态快照不跟随窗口**：`slideWidthDp` 只在转场分支刷新，海报页静止时窗口变宽（平板转屏/分屏）⇒ 播放器偏移小于当前窗宽、停在屏内压住海报页 | 上一轮修复 4 只覆盖转场窗口 | `LaunchedEffect(configuration.screenWidthDp) { 非转场时才刷新快照 }` |
| 11 | 低 | **滑出中连按两次返回会直接离页**（第二次落进 else 分支 → `backToPreviousTarget()`/`finish()`，滑出被中断） | 返回键未考虑 `exiting` 窗口 | 返回键在 `exitingFullscreen` 为真时吞掉 |

## 二、登记不修（本轮新增，低 / 既有）

- 未声明的配置变更（系统深色 / 字体 / 分屏 / 语言）会重建 `DetailActivity` ⇒ 过渡丢失或重播、全屏态重建会重新起播（**既有**；补 `configChanges` 会牵动全局主题响应，不在收尾轮做）
- 过渡 300ms 内用户自己转屏 / 拉宽分屏 ⇒ 快照语义失效（边缘）
- 会话内换集方向不回转（`applyVideoOrientation` 单向只转横屏）；`armVideoSizeWatch` 的"已有尺寸"早退分支不再监听新尺寸（沿用旧尺寸判方向）
- 画质 chip 不发 `playSignal` + 容器到落位才建 ⇒ 极端下命令可能被静默丢弃（今天不可达）
- 海报页常驻"满屏尺寸但在屏外"的容器（旧为 0 高形态）⇒ 真机看一眼有无闪黑/黑边

## 三、已核无问题（本轮）

- 列表/图片不抖动、不重载（`Modifier.offset` 只失效 layout；`PosterBackdrop` 不在偏移层内）
- 播放器层不遮挡海报页（绘制顺序 背景→内容→播放器→拦截层；海报态偏移 = 屏宽）
- 音乐交接 / 投屏 / rollback 与滑入无并发面；`handedOver` 时 `hostDestroy` 不 detach
- 动画协程取消安全（key 变更取消旧协程，不会执行到落定回调）
- 三条进播放入口 plan 一致；画质 chip 语义与旧"直接全屏"等价

## 四、验证

`:app:assembleDebug` 绿 + `:app:testDebugUnitTest` **797 例 / 0 失败**；行尾 LF；三份镜像一致。设备未在线未装机。

## 五、结论

**可收尾，但本轮 5 项修复同样必须重装走查**。新增复验点：
1. 滑入中切内容 / 滑出中点选集 → 不再出现"播放器压住海报页且海报页全不可点"；
2. 平板或大屏（≥600dp）横屏退全屏 → 海报页正常出现（不再只剩模糊底）；
3. 过渡 300ms 内点播放器区域 → 不触发切控制栏 / 暂停 / seek；
4. 过渡中连按返回 → 不会直接离页。

---

# 第三轮审查（2026-10-10，按规范继续：连续两轮有高 ⇒ 未达终止线）

**方法与角度**（换角度，避免重复前两轮）：两路新只读子代理 —— ① **状态机穷举 + 全量对账**（穷举 `fullScreen/entering/exiting/rotating/slideAnim` 可达态 × 出口 × UI 一致；对账第二轮 5 项修复与两轮 12 条登记）；② **规范↔实现逐句对账 + 测试缺口 + Compose/协程专项 + 重复代码**。另补出规范要求的**附录 A 度量盘点**（前两轮遗漏，按 spec 属"本轮未完成"的否决项）。

## 附录 A. 度量盘点（本轮补出）

| 指标 | 命中 | 明细 |
|---|---|---|
| 文件 > 500 行 | 2 | `DetailViewModel.kt` 1010 行、`ui/player/PlayContainer.kt` 821 行（均既有） |
| 方法 > 100 行 | 1 | `DetailScreen.kt:59-280` 顶层 Composable `DetailScreen()` 222 行（**本次改动持续增厚**） |
| 同文件重复 ≥3 次 | 1 | `DetailScreen.kt` 的 `entering \|\| exiting` × 4（本轮已抽 `inTransition`） |
| 同一语义多处手写 | 1 | `fullBox` 判定 2 处（含 Live 页共 3 份实现）→ 本轮抽为 `DetailFullScreenFrame` 两个纯函数 |
| 未达阈值的重复 | 2 | `progress` 公式 × 2（已抽 `slideProgress()`）、"清 rotating + syncFullBoxSideEffects" × 2（已合并） |

## 附录 B. 上轮对账（第二轮 5 项修复 + 12 条登记）

- **第二轮 5 项修复 = 全部真修好**（#7 互斥收敛、#8 `settleRotationAfterExit`、#9 `setTouchBlocked`、#10 快照跟随、#11 返回键吞）—— 逐条代码级复核通过；但 #7/#11 各留一处残留副作用（即本轮中-1 的一环）。
- **登记项 12 条**：11 条仍成立；1 条部分失效（登记 #2"原生 View 侧"已被 #9 修掉，面板侧残留升级为本轮低-1）。

## 已修（5 中 + 3 低）

| # | 级别 | 问题（点成因） | 修法 |
|---|---|---|---|
| 12 | **中** | **`exiting` 无兜底出口**：`settleRotationAfterExit()` 排在清 VM flag 之前，它若抛异常（`setPreviewMode` 链内任一环）⇒ `onExitSlideFinished()` 永不执行 ⇒ `exiting` 永真 ⇒ 返回键被吞 + 拦截层常驻 + 播放器触摸锁 = **无任何用户出口**（结构性，今天无已知触发器） | 退出分支改为**先清 VM flag 再 settle** + `try/finally`；**滑入分支起始也调 `settleRotationAfterExit()`**（清上一次残留，顺带修"滑出被重进打断 ⇒ `rotating` 残留 ⇒ 全屏态按预览态处理"） |
| 13 | **中** | **`fullBox` 两份手写实现**（`DetailScreen` vs `DetailActivity.isFullBox()`），旋转窗口取值**相反**；规范写成"同式"、历史记录各说一套 ⇒ 任何"按文档统一"的后续改动都会引入常态缺陷 | 复核裁定**两处语义本就不同**（内容可见性 / 播放器全屏形态），**故意相反、禁止统一**；抽 `DetailFullScreenFrame.fullBox` / `playerFullScreen` 两个纯函数 + 测试锁"旋转期相反" |
| 14 | **中** | **判据表死分支**：`fullScreenState(requested=true, …)` 与 `facts.portraitVideo` 自 2026-10-09 起生产不可达，4 个单测锁死语义（假信心）；`playbackFacts()` 还为此每次问播放器 | 收敛为 `DetailPlaybackCommands.exitFullScreenState(facts)`；删 `portraitVideo` 字段与 4 个进入用例 |
| 15 | **中** | **滑移基准与旋转叠加**：`slideWidthDp` 快照在"转场中旋转落位/分屏改宽"时永久失配 ⇒ 播放器停在屏内压住海报页、海报位移从 20% 变 44% | **基准改实测宽度**（根 Box `onSizeChanged` → `slideWidthPx`，两处 `offset` 直读），一并消掉上轮 #10 与"key 已消耗"两条 |
| 16 | **中** | **本轮新增判定零单测**（互斥收敛 / 形态判定 / 收尾顺序） | `DetailFullScreenSlide` 纯函数表 + `DetailFullScreenSlideTest`（8 态 × 5 转换 + 互斥不变量）；`DetailFullScreenFrameTest` |
| 17 | 低 | `entering \|\| exiting` × 4、`progress` 公式 × 2 | 抽 `inTransition` 与 `slideProgress()` |
| 18 | 低 | `cancelEntrySlide()` 无幂等守卫 | 收进 `DetailFullScreenSlide.cancelEntry`（`!entering` 或 `exiting` 时拒绝） |
| 19 | 低 | `onConfigurationChanged` 与 `settleRotationAfterExit` 两行同体 | 前者改直调后者 |

## 本轮已核无问题

- `entering`/`exiting` 全部写入点在 VM 内（Screen/Activity/PlayContainer 只读）；`when` 的 exiting 优先后 entering 不可能被饿死
- 去 `snapTo(0)` 后 `slideAnim` 静止态不变量 `slideAnim == (full ? 1f : 0f)` 全路径成立
- `setTouchBlocked` 解锁完备（容器重建 / 音乐交接 / 页面销毁）
- 三条进播放入口最终都收敛到同一转换表，无第三条入口

## 验证

`:app:assembleDebug` 绿 + `:app:testDebugUnitTest` **805 例 / 0 失败**（797 → 805：删 6 例死语义、增 14 例）。设备未在线未装机。

## 结论

本轮**无阻断、无高**，"中"全部修复；但按规范终止线（连续一轮无阻断/高/中），**仍需第四轮**复核本轮修复自身与未覆盖维度，方可收尾。
