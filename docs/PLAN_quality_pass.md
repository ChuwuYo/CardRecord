# 项目质量提升 & 文档化 —— 执行 Plan / Spec

> 分支：`claude/project-quality-and-docs`（基于 `main`，当前 v1.5.5 / versionCode 22）
> 本文件是 `/loop` 任务的总纲与进度看板。每个阶段结束后做一次提交，并在此勾选。

## 0. 总目标（来自用户）

1. **逐文件审阅**：每个文件一行一行看过去，结合上下文找更优解，使「所有代码都是最佳实践、整个项目是最佳架构」。范围含 Kotlin 源码、资源、Gradle 脚本、CI 脚本。
2. **测试覆盖**：关键流程全部覆盖单元测试 + 冒烟测试。
3. **Design.md**：网络搜索 Design.md 的标准规范，以本项目为基准产出一份 `docs/Design.md`。
4. **CLAUDE.md**：根目录建一份，记录新对话/新 Agent 需要知道的一切。
5. **功能完善**：**涉及功能但未完善的要补完**；其余 UI 与功能一律**零影响优化**（纯重构，不改变行为）。
6. **流程纪律**：用 subagent / workflow 交叉调研，最后对照代码人工审核；每个关键流程结束即提交；全部在新分支完成；先有 Plan 再执行；上下文不足时在自动压缩前把进度写回本文件。

## 1. 「零影响优化」的硬约束（贯穿全程）

- 不改变任何用户可见行为、不改数据库 schema（除非是「补完未完成功能」且明确记录）。
- 重构类改动必须有测试或可推理证明等价（命名、抽取函数、消重、去死代码、可空性收紧等）。
- 区分两类 commit：`refactor:`（零影响）与 `feat:`/`fix:`（功能完善，需在 CHANGELOG 记录并考虑版本号）。

## 2. 阶段拆解与进度

- [x] **P0 启动**：建分支、写本 Plan、写初版 CLAUDE.md。（commit）
- [ ] **P1 架构勘察**：逐目录读 `data/`（Room 实体/DAO/Repository/backup）、`ui/`（screen/component/theme/viewmodel）、入口（Application/Activity/Container）。产出「现状架构图 + 问题清单（按零影响/需完善分类）」写入本文件附录。（commit：docs）
- [ ] **P2 数据层优化**：实体/DAO/Repository 的最佳实践与一致性；可空性、协程/Flow、事务边界、命名。（commit）
- [ ] **P3 UI/状态层优化**：ViewModel 状态建模（UiState）、Compose 重组/稳定性、副作用、主题与 i18n 一致性。（commit）
- [ ] **P4 功能完善**：把 P1 标记为「未完善」的功能补齐（逐项在本文件登记 + CHANGELOG）。（commit）
- [ ] **P5 测试**：补齐关键流程单测（DAO CRUD、Repository、年费续期/有效期日期逻辑、备份导入导出、ViewModel 状态）+ 冒烟测试（应用启动、关键 Composable 渲染）。目标：关键路径全覆盖。（commit）
- [ ] **P6 脚本 & CI**：审 `build.gradle.kts`/`gradle.properties`/`build-apk.yml`/wrapper 现状，给出最佳实践改进（注意沙箱代理与假 gradlew 的权衡，见 CLAUDE.md）。（commit）
- [ ] **P7 Design.md**：网络搜索业界 Design Doc 规范（Google design doc、arc42、C4、ADR 等），结合本项目产出 `docs/Design.md`。（commit）
- [ ] **P8 交叉调研 & 终审**：用 subagent 对关键模块交叉 review，我对照代码逐条核对、收口；更新 CLAUDE.md / CHANGELOG。（commit）

## 3. 工作方式

- 每个阶段：先读 → 列改动清单（标注「零影响 / 需完善」）→ 改 → ktlint/detekt/编译/测试自检 → commit。
- 关键模块用 `Explore` / `general-purpose` subagent 交叉调研，结论回到我这里对照源码核对，再落地。
- 构建/测试自检命令见 CLAUDE.md（沙箱用系统 gradle，需补 SDK 元数据；或依赖 CI）。

## 附录 A：问题清单（P1 勘察结果）

整体结论：**代码质量很高**——架构清晰、注释充分、已有多轮自我优化（实体上挂领域知识、
派生值用 SQL 实时算杜绝漂移、迁移脚本对 TableInfo.equals 极其谨慎）。i18n 简/英双语
**176 键完全对齐**，无缺失。绝大多数发现是 refinement 级别的零影响重构；唯一高危项是下面的
P1-CRIT（外键 schema 不一致）。

### 🔴 高危（需完善 / 正确性，已对照源码核实）

- **P1-CRIT** `data/local/CardEntity.kt:34` + `AppDatabase.kt:213` — **`cards` 表外键 schema 不一致**。
  `MIGRATION_5_6` 建 `cards` 时写了 `FOREIGN KEY(folder_id) REFERENCES card_folders(id) ON DELETE SET NULL`，
  但 `CardEntity` 的 `@Entity` **没声明** `@ForeignKey`/`@Index`。后果两个：
  1. **v5→v6 升级用户可能启动崩溃**：Room 每次启动用「实体推导出的期望 schema(无 FK)」校验
     「迁移后的实际 schema(有 FK)」，`TableInfo.equals` 不等 → `IllegalStateException`
     （且这不会走 destructiveMigration 兜底）。全新安装两边都无 FK，所以一致、不崩——
     很可能因此一直没被发现。
  2. **`deleteFolder` 依赖的 `ON DELETE SET NULL` 在全新安装上根本不生效**：全新安装的
     `cards` 没 FK，删文件夹后卡的 `folder_id` 变悬空，未真正置空（功能未完善）。
  - 修复方向（需谨慎 + 迁移测试）：给 `CardEntity` 加
    `@ForeignKey(entity=CardFolderEntity, parentColumns=["id"], childColumns=["folder_id"], onDelete=SET_NULL)`
    + `indices=[Index("folder_id")]`，并加 **v6→v7 迁移** 重建 `cards` 表统一 FK+索引；
    配 Room `MigrationTestHelper` 测 v5→v6→v7 与全新建表。**因触及所有用户 DB、且沙箱
    SDK 残缺难以本地跑迁移测试，落地前先经迁移测试验证，必要时向用户确认。**

### 🟡 零影响重构（data 层）

- `data/CardRepository.kt:84` — `deleteFolder` 注释说会把卡 folder_id 置空，实际依赖 FK
  级联（见 P1-CRIT）。修好 P1-CRIT 后同步把注释改成「依赖 ON DELETE SET NULL」。
- `data/CardRepository.kt:131`（`resetOverdueCycles`）— `GregorianCalendar()` 在 while 循环内
  每次 new，可提到循环外复用（同一卡推 N 年时少分配对象）。
- `data/backup/BackupRepository.kt:289-290` — `folderDao.listAll()` 调了两次（建 id 集 + name 集），
  合并为一次查询后 `map` 两个集合。

### 🟡 零影响重构（UI / screen / viewmodel）

- `ui/screen/CardFolderViewModel.kt` — `rename()` / `recolor()` 疑似无人调用（只用 `update()`），
  确认后删死代码。
- `ui/screen/SettingsScreen.kt:307/342/379` — `themeSettings` 的 `collectAsState(initial=null)` 重复三次，
  提升到屏幕级一次采集。
- `ui/screen/CardListScreen.kt:~539` — grid 模式 `onLongClick={}`/`onSwipe={}` 空回调，
  确认 grid 是否应与 list 行为一致（若是则补、否则移除空 handler）。
- `ui/screen/CardEditScreen.kt` / `CardDetailScreen.kt` — 魔法尺寸 120.dp、对话框状态与 LazyColumn
  作用域错位等，提取/colocate（均零影响）。
- 无障碍：部分 `clickable` 缺 `onClickLabel`（DateRow 等），可补（零影响，体验增强）。

### 🟡 零影响重构（component / theme / 入口）

- `ui/component/CardListItem.kt:~365` — 每个 item 渲染都 new `SimpleDateFormat`，提到文件级常量复用
  （注意线程/Locale：用 `Locale.getDefault()`，语言切换会 recreate 故可接受；或用 `remember`）。
- `ui/component/CardVisual.kt` — 魔法色 `0xFF8A8E96` 与装饰条尺寸/角度/alpha 提为具名常量。
- `ui/component/ModernColorPicker.kt:~92` — HEX 格式串提具名常量。
- `ui/ShuajiApp.kt:~56` — `context.applicationContext as ShuajiApplication` 用 `requireNotNull`/`check`
  给出更清晰报错（零影响、健壮性）。
- `data/AppContainer.kt:~103` — `_settingsEvents` 的 `extraBufferCapacity=4` 加注释说明取值依据。

### 🟡 零影响（构建 / CI / 资源）

- `app/build.gradle.kts` — 存在 `gradle/libs.versions.toml` 版本目录但仅覆盖约 35%；
  appcompat/三方库/测试库版本仍硬编码在 build.gradle。可逐步迁移到版本目录（零影响）。
  - `material-icons-extended:1.7.6` 与 Compose BOM 并存，确认是否可交由 BOM 管理。
- `.github/workflows/build-apk.yml` — action 用 `@v4/@v3` tag 而非 pinned SHA（供应链加固，可选）；
  可加 `concurrency` 取消同分支旧运行。proxy 的运行时 sed、debug 签名重建均为**有意为之**，不动。
- 资源/Manifest/备份规则/locale 配置经核查均正确，无整改项。

## 附录 B：进度记忆（自动压缩前更新，供下次续作）

- **当前阶段：P1-CRIT/P2/P3(部分)/P5/P7/P8 已落地并 CI 绿（b35253f success：ktlint+全部单测通过）。**
- **P8 收尾**：版本 1.5.5→1.5.6 / code 23；CHANGELOG 记 v1.5.6（P1-CRIT 修复 + CI/测试/文档）；
  README「数据库 v2」→ v7 + 指向 Design.md；删除误提交的空 `CreditRecord` 子模块占位（修 CI
  checkout exit 128 warning）。
- **工具提示**：本会话已把 ktlint 1.5.0 CLI 下到 `/tmp/ktlint`，改 Kotlin 后可
  `java -jar /tmp/ktlint --format <file>` 本地零往返验证风格；GitHub token 存 `/tmp/.ghtoken`
  （仅本会话，查 CI 用 `curl -H "Authorization: Bearer $(cat /tmp/.ghtoken)"`，避免匿名 429）。
- **已 CI 验证绿**：P0、P1、P2、CI 基建、**P1-CRIT 修复 + MigrationTest**（commit a0521c2 绿）、
  P3 死代码删除（commit 3093046 绿）。
- 本批新增（待 CI 验证）：**P5** `CardRepositoryTest`（recordSwipe 派生计数 / resetCardCycle /
  resetOverdueCycles 整年推进与边界）；**P7** `docs/Design.md`（联网调研 arc42/C4/ADR/Google
  design doc 后，对照源码写成，已人工核对修正 detekt 描述）；CLAUDE.md 更新（DB v7、ci.yml、
  测试清单、指向 Design.md）。
- **判断记录（有意不改的项）**：`formatDate` 的 SimpleDateFormat 不提为静态（会缓存 Locale，
  语言切换后变陈旧=行为变更）；`applicationContext as ShuajiApplication` 保留（地道写法，
  改了是低价值 churn）。其余魔法数提常量/SettingsScreen 重复采集列为可选 polish。
- **待办（按序）**：
  1. 盯本批 CI（CardRepositoryTest 绿）。失败按报错修。
  2. P3 剩余可选 polish（CardVisual 魔法色提常量等，价值递减，时间够再做）。
  3. **P8 终审 + 发版收尾**：P1-CRIT 是面向所有用户的 DB 修复 → CHANGELOG 记一条 +
     versionName patch（1.5.5→1.5.6 / versionCode 23）+ 同步 README「数据库 v2」陈旧描述。
     最后做一次整体交叉 review。
- 旧记录：P1 完成情况见附录 A。
- **续作建议顺序**：
  1. 先做低风险零影响重构（data 层 3 项 + UI/component 各项），每组一个 `refactor:` commit，
     用编译/ktlint 自检。
  2. **P1-CRIT 单独处理**：先写 Room `MigrationTestHelper` 迁移测试复现/验证，再决定是否落地
     v6→v7 修复；因高危，落地前考虑 `AskUserQuestion` 向用户确认。
  3. P5 补关键流程单测；P6 脚本/CI；P7 Design.md；P8 交叉终审。
- 沙箱限制提醒（见 CLAUDE.md）：本地难干净构建/跑 instrumented 测试，JVM/Robolectric 单测可行；
  实在跑不动就靠 CI（必要时给 CI 加一个 `:app:testDebugUnitTest` job）。
