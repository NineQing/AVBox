# 审查报告：移除外部播放器出口（2026-10-08，batch4）

审查对象 = 工作区未提交改动：删 manifest 5 条 `<package>` + 删 `player/thirdparty` 四个适配类（MX / Reex / Kodi / VLC）+ `PlayerHelper` 收口 + `pl` / `PLAY_TYPE` 归一化 + 三语 i18n 清理 + 相关文档同步。

## 0. 覆盖度声明

- 两轮：R1 主审（自查 + 消费面搜索）；R2 独立只读子代理（code-explorer，medium，不带 R1 结论）。
- 核对面：`pl`（播放配置 JSON）全部读写点、`PLAY_TYPE`（KV）全部读写点、`getExistPlayerTypes()` 全部消费方、`PlaybackStarter` 外部出口分支、`RemoteTVBox` 推送链、i18n 三语与死 key、残留引用/资源/字符串、manifest 包可见性（全仓无 `queryIntentActivities` / `resolveActivity` / 他包 `getPackageInfo` / 按包名 `setPackage`）。
- 命令：`Select-String`（`"pl"` / `optInt("pl"` / `put("pl"` / `PLAY_TYPE` / `getExistPlayerTypes` / `MXPlayer|Reex|Kodi|VlcPlayer|player_mx|...`）、`i18n_check_keys.py` / `i18n_align.py` / `i18n_gate.py`、`gradlew :app:assembleDebug :app:testDebugUnitTest` + 测试 XML 计数。

## 1. 结论

无阻断 / 无高。R1 自查 1 条中（已修）；R2 报 6 条（1 中 5 低）：4 条已修、3 条登记（含 1 条"既有被放大"）。

## 2. 发现与处置

| # | 发现 | 级别 | 维度 | 处置 |
| --- | --- | --- | --- | --- |
| 1 | 历史 `PLAY_TYPE=10..14`：设置页「播放内核」显示归一化后的「Media3」，但解码方式 / Anime4K 两行仍按 `state.playType == 2` 置灰 ⇒ 显示与禁用态不一致 | 中 | 本次引入 | **已修**：`App.initParams` 启动期把 `playType > 2 && playType != 13` 归一回 2（保留 13，避免抹掉用户的 TVBox 选择）；`PlaySettingsPage` 的 `playerTypes` / `currentPlayType` 提升到分组外、两行 `enabled = currentPlayType == 2` |
| 2 | 文案仍写「调用外部播放器%1$s进行播放」，而该 key 唯一用途已是「推送到附近TVBox」⇒ 口径矛盾（"调用外部播放器附近TVBox"） | 低 | 本次遗漏 | **已修**：三语改「推送到%1$s进行播放」/`Casting to %1$s` /「推送到%1$s進行播放」（key 名保留，避免扩大改动面） |
| 3 | `PlayerHelper.getPlayersInfo()` 变为死代码（全仓无调用方） | 低 | 本次引入 | **已删** |
| 4 | 活规范置灰判据仍写 `state.playType != 2`，实现已改 `currentPlayType == 2` | 低 | 本次遗漏 | **已修**：`skill/avbox-mobile-ui-spec.md` + `.codebuddy` / `.trae` 两镜像 |
| 5 | TVBox 推送「假成功」：`RemoteTVBox.run` 无论 POST 成败都返回 true，且 `PlaybackStarter` **先 `releasePlayer()` 再推送** ⇒ 设备离线时内播已被停、UI 仍提示"成功"。本次删除外部播放器后 13 成为唯一外部出口，可达面被放大 | 中 | **既有被放大** | **登记不改**：修法需给推送加结果回传/超时并调整释放时机，牵动播放编排（备选见 §4） |
| 6 | 设置页归一化只落在渲染层：`PLAY_TYPE=13` 且 KV `REMOTE_TVBOX` 缺失时，行显示「Media3」但 KV 仍 13；此后若投屏扫到设备（`invalidatePlayersExistInfo`）该行会变回「附近TVBox」 | 低 | 本次引入 | **登记不改**：列表按"当前可用性"渲染是合理行为；不写回 KV 是为保留用户原选择（写回会永久丢弃）。真实可达性「不确定」（代码中无清 `REMOTE_TVBOX` 的路径） |
| 7 | 「播放内核」列表只有 `[2]` 时点击无任何变化（未开过投屏面板的设备） | 低 | 既有叠加 | 登记（可选：单项时按解码/Anime4K 同款置灰） |

## 3. 已核对无异常（R2 结论摘要）

- `pl` 归一化集中在 `PlaybackConfigDelegate.initPlayerCfg`（含源站 `playerType`）与 `App.initParams`；其余消费方（`PlayerConfigDelegate` / `PlayerActionsDelegate` / `PlaybackRetryDelegate` / `PlaybackStarter` / `PlayContainer`）拿到的只可能是 2 或"存在表内的 13"，无未归一化决策路径。
- `PLAY_TYPE` 仅 4 处读写（`App` 归一化、`SettingsState` 读取、设置页写入、`PlaybackConfigDelegate` 作默认）。
- `getExistPlayerTypes()` 消费方在 `[2]` / `[2,13]` 下无越界、空表、死循环（`playersExist[2]` 硬编码为 true；`sheetPlayerOrder` 保证 2 在首位；`applyPlayer` 同值提前 return）。
- `PlaybackStarter` 新条件不误挡合法推送：无 `REMOTE_TVBOX` 时 `getAvalibleActionUrl()` 恒为 `""`，推送本就不可用；`KV` 为 MMKV（键缺失返回 null）、`getPlayersExistInfo()` 每进程只建一次（2 项）、`RemoteTVBox.run` 走 `enqueue` + 1s 超时，主线程不阻塞。
- `PlaybackRetryDelegate`（软解回退排除）与 `PlayContainer.alignInstanceConfigOnTakeover` 的 `pl >= 10` 判断在只剩 13 的语义下仍成立。
- 无残留：全仓检索无被删类/key 的代码引用（仅历史文档与 `TxtSubscribe` 的 `#EXTVLCOPT:` / `#KODIPROP:` 播放列表语法）；`player/thirdparty` 只剩 `RemoteTVBox.kt`；manifest 删除无包可见性回归（无受影响 API 调用）。

## 4. 收尾判定：可收尾

- 终止线：两轮均无阻断 / 高。唯一「中」（#5）属既有缺陷、本次仅放大可达面，修法牵动播放编排 ⇒ 转独立笔，不阻塞本批。
- 验证：`:app:assembleDebug :app:testDebugUnitTest` BUILD OK、**755 例 / 0 失败**；`i18n_check_keys` **545/545**（无未用 / 未声明 / 重复文案）、`i18n_align` en / b+zh+Hant / zh-RHK --subset 全 PASS、`i18n_gate` ui 层 0 处；触及文件行尾 LF。
- 备选（#5 的修法，未实施）：给 `RemoteTVBox.run` 加结果回调（okhttp 回调 → 主线程回传），`PlaybackStarter` 等推送结果再决定是否 `releasePlayer()`；或退一步只把"假成功"改成"已发出推送"的中性提示。
