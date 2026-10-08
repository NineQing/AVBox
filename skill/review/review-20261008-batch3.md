# 审查报告：内封多字幕指纹补 `Format.id`（2026-10-08，batch3）

审查对象 = 工作区未提交改动（3 代码/测试文件 + 文档同步）：

- `app/src/main/java/com/github/tvbox/osc/util/TrackMemory.kt`（`textFingerprint` → 5 段，含 `id`/`label`）
- `app/src/main/java/com/github/tvbox/osc/player/engine/EngineTrackSelection.kt`（`formatKey` 字幕分支传 `fmt.id` / `fmt.label`）
- `app/src/test/java/com/github/tvbox/osc/util/TrackMemoryTest.kt`（Java→Kotlin 迁移 + 新增 8 例）
- 文档：`skill/avbox-mobile-ui-spec.md`、`skill/history/features.md`、`skill/avbox-code-review-spec.md`（+ 两镜像）

## 0. 覆盖度声明

- 两轮：R1 主审（消费方全量搜索 + diff 逐行）；R2 独立只读子代理（code-explorer，medium，不带 R1 结论）。
- 核对面：`TrackMemory` 全部方法与唯一调用链（`EngineTrackSelection.pick/locate/restoreSubtitleByMemory/loadDefaultSubtitleTrack/ensureSubtitleTrackSelected`）、字幕记录全部读取方（`ui/player/PlayContainerSubtitles.applySubtitleDecision` 及三态前缀判定）、音/视轨指纹与 `formatKey` 音/视分支、直播/音乐空键路径、外挂字幕链路、测试等价性、文档口径。
- 命令与工具：`Select-String`（`textFingerprint`/`loadSubtitle`/`formatKey`/`12 个` 等模式）、`git show HEAD:<file>` 对账、`gradlew :app:testDebugUnitTest :app:assembleDebug`、测试 XML 计数。

## 1. 结论

R1：无阻断 / 无高 / 无中（1 条文档过期，低）。R2：无阻断 / 无高，1 中 3 低（其中 1 低为"证据受限"，已用 `git show` 对账关闭）。

## 2. 发现与处置

| # | 发现 | 严重度 | 维度 | 处置 |
| --- | --- | --- | --- | --- |
| 1 | `label` 参与精确匹配键：若 `label` 跨集漂移，精确匹配 miss → 回落默认（语言段为空时直接 -1）。最坏结果 = 修复失效，**不劣于改前**（改前同样只到第一条） | 中 | 本次引入 | 登记为**走查观察项**（判据 §4-3）；实证面：mp4 tx3g 的 label 通常为 null、mkv TrackName 固定，属假设性风险。备选方案：把 label 移出精确键（代价 = `id` 缺失时失去区分能力） |
| 2 | 老 3 段记录（如 `T//x-quicktime-tx3g`）升级后首次播放必然精确 miss → 回落默认（一次性、重选即升级；语言非空者仍按唯一语言平滑命中） | 低 | 本次引入 | 设计内，已在 `features.md` 登记，不修 |
| 3 | 测试缺"老记录 + 语言段为空 / 语言歧义"两条升级路径断言 | 低 | 本次引入 | **已修**：新增 `pick_textLegacyRecordWithoutLanguageStaysMiss`（2 断言） |
| 4 | Kotlin 迁移"逐例等价"无法仅从工作区自证 | 低 | 本次引入 | **已关闭**：`git show HEAD:...TrackMemoryTest.java` 对账 —— 15 个旧方法全部保留（缺失集为空）、断言数 42（旧）+ 9（新增用例）= 51 逐条对上 |
| 5 | `history/features.md` 两份镜像未补本次实施记录 | 低 | 本次引入 | 已按既有欠账口径在活档 `features.md` 内明示（镜像落后一条既有记录，不顺手补齐） |
| 6 | R1 自查：`skill/avbox-code-review-spec.md`「唯一 Java 存量 = 12 个测试文件」过期 | 低 | 本次引入 | **已修**：12→11（`TrackMemoryTest` 已迁 Kotlin），`.codebuddy` / `.trae` 两镜像同位置同步 |

## 3. 既有登记（非本轮引入，未扩大）

- 音轨/视轨指纹仍是窄维度（音频 `A/语言/编码/声道`、视轨 `V/编码/宽x高`）：同语言同编码的多条音轨撞车时仍退化为第一条。本次未扩面（P2）。
- `pick` 精确匹配命中多条按候选顺序取第一条（已写进活规范并由 `TrackMemoryTest` 固化）。

## 4. 收尾判定：可收尾（代码面）

- 终止线：R2 无阻断 / 无高；唯一「中」为字段稳定性假设，最坏结果 = 修复失效（不劣于改前）⇒ 转真机走查，不再开轮次。
- 验证：`.\\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL；**755 例 / 0 失败 / 0 错误**（`TrackMemoryTest` 23 例）。
- 走查判据（按优先级）：
  1. 多同质内封字幕片源里选「字幕N」→ 切集后仍为字幕N（不再回第一条）。
  2. `echo-track-memory save text=` 与 `restore text fp=` 两端一致，且 fp 的 **id 段非空**（形如 `T//3/application x-quicktime-tx3g`）。
  3. 若见 fp 的 **label 段跨集变化**导致两端不等 → 记反例（回到发现 #1，把 label 移出精确键）。
  4. 升级路径：装新版本后第一次播放旧片，字幕回落默认属预期，重选一次后跨集应保持。
