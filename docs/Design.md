# 刷记 (CardRecord) 设计文档 / Design.md

> **本文档结构参考**：综合 [arc42 模板](https://arc42.org/)（上下文/约束/方案策略/构建块/横切关注/风险）、
> [C4 模型](https://c4model.com/)（用 Container / Component 视角画分层）、以及
> [Google Design Doc 实践](https://www.industrialempathy.com/posts/design-docs-at-google/)（Context & Scope / Goals / Non-Goals / Alternatives Considered）。
> 关键技术取舍以轻量 [MADR / ADR](https://adr.github.io/madr/) 风格记录。
> 作为单模块移动应用，本文**有意裁掉**企业级章节（部署拓扑、容量规划、SLA 等），只保留对本项目有意义的部分。
>
> **定位区分**：本文讲 **WHY / 架构与取舍**；`CLAUDE.md` 讲 **HOW / 环境与构建坑**（沙箱 gradlew 桩、写死代理、tag push 限制等），二者不重复。运行期注意事项请直接读 `CLAUDE.md`。

---

## 1. 概述与背景 (Context & Scope)

刷记是一款**本地优先（local-first）**的原生 Android 应用，用于追踪「卡片消费笔数」。
某些信用卡要求一年刷满 N 笔才能免次年年费，而银行 App 通常不展示这个累计笔数——本应用让用户手动记录每笔消费，自动算出 `当前笔数 / 所需笔数` 进度，并在年费结算日自动开启新周期。

- 包名 `com.shuaji.cards`，当前 v1.5.5 / versionCode 22（见 `app/build.gradle.kts`）。
- 单 Gradle 模块（`:app`），无后端、无账号体系。所有数据存设备本地（Room + DataStore），支持 JSON 导入/导出。
- 最低 Android 8.0 (API 26)，target/compile SDK 36。

## 2. 目标 (Goals)

- 多卡管理：卡名、发卡行、脱敏卡号、主题色、卡面（横/竖版 + 7 个卡组织预设或自定义图）。
- 笔数进度：`x / y` 直观显示，剩余笔数提示。
- 年费周期：记录「下次年费结算日」，到期自动续期（清零笔数 + 推一年）。
- 文件夹分组、卡片有效期记录。
- Material 3 现代化外观（动态取色、edge-to-edge、expressive shapes）。
- 中/英双语，应用内可切换。
- 用户对自己数据有完整的导出/恢复权利（JSON，可人工 inspect）。

## 3. 非目标 (Non-Goals)

- **无云同步 / 无账号 / 无后端**。跨设备迁移靠手动导出导入。
- **不做记账**：不记金额、商户、备注明细——付款 App 里更准。流水只记「时间 + 所属卡」。
- 不抓取银行数据、不做 OCR、不接支付 SDK。
- 非生产级签名（release 复用 debug keystore，详见 ADR-5）。
- 不支持多模块 / 动态特性 / 即时应用。

## 4. 约束 (Constraints)

- 工具链：Kotlin 2.1.20、AGP 8.12.0、Gradle 8.14.4、Java 17。
- Compose BOM 2025.05.00 + Material 3；Room 2.7.2（KAPT）；DataStore Preferences 1.1.1。
- 第三方库刻意精简：`skydoves/colorpicker-compose`（HSV 色环）、`materialkolor`（种子色→MD3 配色）、`coil3`（图片加载）、`kotlinx-serialization`（备份 JSON）。
- 沙箱/CI 环境有特殊限制（gradlew 桩、写死代理、tag push 被拦）——见 `CLAUDE.md`，本文不复述。

---

## 5. 技术栈与架构总览 (Solution Strategy)

单向数据流（UDF）的经典 Android 现代架构：

- **UI 层**：Jetpack Compose + Material 3。每个屏幕一个 `*Screen` Composable + 一个 `*ViewModel`。
- **状态**：ViewModel 暴露 `StateFlow<*UiState>`，UI 只读 + 回调；不在 Composable 里持有业务状态。
- **领域/仓库层**：`CardRepository` / `SettingsRepository` / `BackupRepository`，只暴露「业务用例」，不把 Room Entity / DAO 泄漏给上层（见 ADR-1）。
- **数据层**：Room（结构化数据：卡 / 流水 / 文件夹）+ DataStore Preferences（主题偏好）。语言偏好由 AppCompat 自己持久化，不占 DataStore。
- **依赖注入**：手写轻量容器 `AppContainer`，由 `ShuajiApplication` 持有，无 Hilt/Dagger（见 ADR-6）。

数据流向（写）：`Screen → ViewModel → Repository → DAO → SQLite`；
数据流向（读）：`DAO Flow → Repository Flow → ViewModel.stateIn(StateFlow) → Compose collectAsState`。
写入流水后无需手动刷新 UI——`currentCount` 由 SQL 实时算（ADR-1），Room 的 `Flow` 自动重发。

---

## 6. 容器视图 (C4 — Container)

```
┌──────────────────────────── Android 设备 ────────────────────────────┐
│                                                                       │
│   ┌──────────────┐        刷记 App (单进程 / 单模块 :app)              │
│   │  使用者      │───────►┌────────────────────────────────────────┐  │
│   │ (持卡人)     │ 触摸    │  Jetpack Compose UI + ViewModel        │  │
│   └──────────────┘        │  (StateFlow UiState, 单向数据流)        │  │
│                           └───────────────┬────────────────────────┘  │
│                                           │ 业务用例调用               │
│                           ┌───────────────▼────────────────────────┐  │
│                           │  Repository 层                          │  │
│                           │  CardRepository / SettingsRepository /  │  │
│                           │  BackupRepository                       │  │
│                           └───┬───────────────┬───────────────┬─────┘  │
│                               │               │               │        │
│                  ┌────────────▼───┐  ┌────────▼──────┐  ┌─────▼──────┐ │
│                  │ Room (SQLite)  │  │ DataStore     │  │ SAF (用户   │ │
│                  │ shuaji.db v7   │  │ shuaji_prefs  │  │ 选文件 URI) │ │
│                  │ cards /        │  │ 主题偏好      │  │ 导入/导出   │ │
│                  │ transactions / │  └───────────────┘  │ JSON 备份   │ │
│                  │ card_folders   │                     └────────────┘ │
│                  └────────────────┘                                    │
└───────────────────────────────────────────────────────────────────────┘
            外部资源（构建期）：simple-icons 卡组织 logo → VectorDrawable
```

## 7. 组件视图 (C4 — Component，源码地图)

```
com.shuaji.cards
├─ MainActivity (AppCompatActivity)        入口，enableEdgeToEdge，collect 主题设置
├─ ShuajiApplication (Application)          持有 AppContainer；onCreate 跑启动续期
├─ data/
│  ├─ AppContainer.kt                       手写 DI 容器 + 全局事件 SharedFlow
│  │                                        (cycleAutoResetEvents / settingsEvents)
│  ├─ CardRepository.kt                     卡/流水/文件夹用例 + 自动续期逻辑
│  ├─ SettingsRepository.kt                 DataStore 主题偏好 (themeMode/colorSource/seed)
│  ├─ CardNetworkProvider.kt                7 个卡组织枚举（卡面预设）
│  ├─ backup/
│  │  ├─ BackupModels.kt                    BackupBundle / ImportMode / ImportResult
│  │  └─ BackupRepository.kt                导入导出（REPLACE/MERGE，事务+可取消+流式）
│  └─ local/
│     ├─ AppDatabase.kt                     @Database v7 + 6 条 Migration + ALL_MIGRATIONS
│     ├─ CardEntity / TransactionEntity / CardFolderEntity
│     ├─ CardDao (含 CardWithCount 派生计数查询) / TransactionDao / CardFolderDao
└─ ui/
   ├─ ShuajiApp.kt                          顶层 Scaffold + NavHost + 全局 SnackbarHost
   ├─ AppLanguage.kt                        per-app language 枚举（BCP-47）
   ├─ ViewModelFactories.kt                 ViewModel 工厂（注入 Repository）
   ├─ theme/ (Theme/Type/Shape/ColorHex)    MD3 主题 + MaterialKolor 取色
   ├─ component/ (CardVisual/CardListItem/ModernColorPicker)
   └─ screen/ (CardList/CardDetail/CardEdit/CardFolder/Settings + 各 ViewModel)
```

ViewModel 不直接 new，全部经 `ViewModelFactories.kt` 注入 `CardRepository` 等依赖；
跨页面事件（启动续期提示、设置页操作回执）不走 ViewModel 持有的局部 SnackbarHost，而是经 `AppContainer` 的 `SharedFlow` 推到顶层 `ShuajiApp` 的全局 `SnackbarHost`（避免跳页/锁屏丢消息，见 `AppContainer.kt` 注释）。

---

## 8. 数据模型 (Data Model)

Room 数据库 `shuaji.db`，当前 schema **version = 7**（`AppDatabase.kt`），三张表：

### CardEntity (`cards`)
卡片静态属性：`id, name, bank, card_number_masked, valid_until_millis?, next_due_date_millis?,
required_count, color_argb, note, image_uri?, image_source_type, image_provider_key?,
card_orientation, folder_id?, created_at_millis`。

- **不存 `current_count`**——派生自 `transactions` 的 `COUNT(*)`（见 ADR-1）。
- 卡面来源三态：`image_source_type ∈ {NONE, PROVIDER, USER}`（`ImageSourceType` 枚举）。
- 朝向 `card_orientation ∈ {LANDSCAPE(1.586:1), PORTRAIT}`；宽高比作为领域知识挂在 `CardOrientation` 枚举上，UI 只消费（`CardEntity.kt`）。
- **外键**：`folder_id → card_folders.id`，`ON DELETE SET NULL`（删文件夹时卡片归「未分类」），并声明 `@Index("folder_id")`。

### TransactionEntity (`transactions`)
极简两字段 + 主键：`id, card_id, occurred_at_millis`。

- **外键**：`card_id → cards.id`，`ON DELETE CASCADE`（删卡级联删流水）。`@Index("card_id")`。
- 没有金额/商户/备注（非目标）。「记一笔」= INSERT；「重置」/「撤销」= `DELETE WHERE card_id=?`。

### CardFolderEntity (`card_folders`)
`id, name, color_argb, sort_order, created_at_millis`。已删除历史的 `icon_key`（写而不读死字段，见 ADR-2）。

### 派生视图 CardWithCount（非表，DAO 查询投影）
```sql
SELECT c.*, COALESCE(t.cnt,0) AS current_count, t.last_at AS last_swipe_at_millis
FROM cards c
LEFT JOIN (SELECT card_id, COUNT(*) cnt, MAX(occurred_at_millis) last_at
           FROM transactions GROUP BY card_id) t ON t.card_id = c.id
```
Repository 对外只给 `CardWithCount`，UI 永远拿到新鲜的 `currentCount`，不可能漂移。

### 关系图
```
card_folders (1) ──< (0..N) cards (1) ──< (0..N) transactions
                 SET NULL          CASCADE
```

> 注：`Entity` 同时标 `@Serializable`，直接作为备份 JSON 的元素导出——`@Entity`(KAPT) 与 `@Serializable`(compiler plugin) 是两条独立通路，互不干扰（见各 Entity 文件注释）。

---

## 9. 数据库迁移策略 (Migration)

历史版本（`AppDatabase.kt` 内 6 条 Migration，`ALL_MIGRATIONS` 顺序注册）：

| 版本 | 内容 |
|------|------|
| v1→v2 | 新增 `image_source_type` / `image_provider_key` / `card_orientation`（旧表名 `credit_cards`） |
| v2→v3 | 新增 `card_folders` 表 + `credit_cards.folder_id` |
| v3→v4 | 修复 v1.3.0 写坏的 schema（误加 DEFAULT、多建索引导致校验失败被清库）；重建 `card_folders` |
| v4→v5 | 主表 `credit_cards` RENAME 为 `cards`（历史 bug：曾误把 version 停在 4，v1.4.0 才真正升上去） |
| v5→v6 | data 大瘦身：删 `transactions` 的 amount/merchant/note、删 `cards` 的 current_count/cycle_start/archived、删 `card_folders.icon_key` |
| v6→v7 | **统一 `cards` 外键 schema**（详见下） |

**核心原则——迁移 SQL 必须逐字符匹配 Room kapt 生成的 schema**：
Room 启动时 `onValidateSchema` 用 `TableInfo.equals` 对比磁盘表与实体期望。
列定义、`NOT NULL`、`DEFAULT`、索引名/列、外键定义任一对不上即校验失败抛 `IllegalStateException`。
SQLite 不支持 `ALTER TABLE DROP COLUMN`/改外键，故删列/改外键统一走「**建新表 → 复制数据 → 删旧表 → 重命名 → 重建索引**」四步，并用 `PRAGMA foreign_keys=OFF/ON` 包住中间步骤。

**v6→v7 修复的 FK 不一致（重点）**：v7 之前 `CardEntity` 没声明 `@ForeignKey`/`@Index`，但 `MIGRATION_5_6` 的建表 SQL 写了 `ON DELETE SET NULL` 外键，产生两条互相矛盾的历史路径：

- **全新装到 v6**：`cards` 表由实体生成 → **无**外键 → `deleteFolder` 依赖的 SET NULL 形同虚设。
- **v5→v6 升级**：磁盘表**有**外键，但与「实体期望无外键」对不上 → Room 校验崩溃、回滚，用户实际停在 v5。

v7 同时给 `CardEntity` 补上 `@ForeignKey(SET_NULL) + @Index("folder_id")`，并用 `MIGRATION_6_7` 重建 `cards` 表把两条路径都收敛到「带外键 + `index_cards_folder_id`」的统一形态。
回归测试见 `MigrationTest.kt`（手建 v5 库 → 跑 `ALL_MIGRATIONS` → 强制 open 触发校验 + 断言 folder_id 保留、删文件夹 SET NULL 生效）。

兜底：`fallbackToDestructiveMigration(true)` 仍保留，但迁移写对后该分支永不触发。

---

## 10. 关键设计决策与取舍 (ADRs)

> 轻量 MADR 风格：每条 = 决策 / 理由 / 取舍。

### ADR-1：`currentCount` 派生，不冗余存储
**决策**：`cards` 表不存 `current_count`，改由 `transactions` 的 `COUNT(*)`（LEFT JOIN + GROUP BY 子查询）实时算，Repository 只暴露 `CardWithCount`。
**理由**：旧实现双源（流水 + 计数）易漂移——任一被错改、或删最后一笔时忘回滚计数，进度就不准。
**取舍**：每次查询多一次聚合，但卡/流水量级极小（一年几十条），开销可忽略，换来「不可能漂移」。

### ADR-2：字段精简——「凡存在字段必有 UI 消费路径」
**决策**：任何 Entity 字段都必须有读取它的 UI/查询路径，否则删除。据此删了 `cycle_start_millis`、`card_folders.icon_key`、`archived`、流水的金额/商户/备注。
**理由**：「写而不读」的死字段制造维护负担与误解，并迫使迁移代码无谓搬运。
**取舍**：偶尔需要为新需求重新加列 + 写迁移；但默认从简，按需扩展。
**推论**：`next_due_date_millis` 因此必须有「到期自动续期」行为（`resetOverdueCycles`，`ShuajiApplication` 启动时跑），否则就违反本原则（v1.4.2 补上）。

### ADR-3：i18n 走 AppCompat per-app locale
**决策**：应用内语言切换用 `AppCompatDelegate.setApplicationLocales`（`AppLanguage.kt`），`MainActivity` 继承 `AppCompatActivity`，Manifest 配 `autoStoreLocales`。
**理由**：官方 per-app language 方案，Android 13+ 走系统能力、13 以下由 AppCompat 委托自动持久化与恢复，无需自己存语言、重建 Activity。
**取舍**：必须用 `AppCompatActivity`（而非纯 `ComponentActivity`），Compose 在其上正常工作。语言偏好不进 DataStore（AppCompat 自管）。新增一门语言只需「加枚举项 + `values-<tag>/strings.xml` + `locales_config.xml`」三步。

### ADR-4：种子色配色交给 MaterialKolor
**决策**：CUSTOM 颜色与低版本动态色回退由 `materialkolor` 的 `rememberDynamicColorScheme`（封装 Google material-color-utilities 的 HCT tonal palette 官方算法）生成整套 MD3 配色。Android 12+ 的 SYSTEM_DYNAMIC 仍用平台 `dynamicLight/DarkColorScheme`。
**理由**：自己用 HSL 近似无法保证 primary/secondary/容器色协调且符合 MD3。
**取舍**：多一个依赖，但避免重造 Material You 算法（`Theme.kt`）。

### ADR-5：release 复用 debug keystore
**决策**：`signingConfigs.releaseDebugSigned` 读 `~/.android/debug.keystore`（凭据全 `android`）。
**理由**：本仓库尚无生产密钥；未签名 APK 会因 `INSTALL_PARSE_FAILED_NO_CERTIFICATES` 装不上。目的仅为产物可安装。
**取舍**：**不是生产级签名**，上架前必须换正式密钥。CI 用 `keytool` 现造 debug.keystore（`build-apk.yml`）。

### ADR-6：手写 DI，不用 Hilt
**决策**：`AppContainer` 接口 + `DefaultAppContainer` 实现，`ShuajiApplication` 持有；ViewModel 经 `ViewModelFactories.kt` 注入。
**理由**：单模块、依赖数量少（三个 Repository + 几个 DAO），Hilt 的注解处理 + 编译开销 + 学习成本不划算。
**取舍**：手动维护构造图；但容器同时承担「全局事件总线」（`cycleAutoResetEvents` / `settingsEvents` 的 `SharedFlow`），职责清晰、可测。

---

## 11. i18n、主题与动态取色

- **i18n**：简中 + English，`res/values/strings.xml` 与 `res/values-en/strings.xml` 必须同步；禁止 Composable 写死中文。切换走 ADR-3。
- **主题三维模型**（`SettingsRepository.ThemeSettings`）：`themeMode`(SYSTEM/LIGHT/DARK) × `colorSource`(SYSTEM_DYNAMIC/CUSTOM) × `seedColorHex`。旧版二维 `useDynamicColor:boolean` 保留读取兼容（v1.5.1 迁移）。
- **取色**：见 ADR-4。默认品牌主色 `DefaultBrandPrimary = #0061A4`（冷调深蓝，信任感），用作动态色回退与默认种子；不内联十六进制。
- edge-to-edge（`enableEdgeToEdge`）+ MD3 expressive shapes。

---

## 12. 备份 / 导入导出设计

`BackupRepository`，JSON 格式（`BackupBundle` 带 `SCHEMA_VERSION`，三类实体直接序列化），经 SAF URI 读写（不申请存储权限）。

**两种导入语义**：
- **REPLACE**：先删 `cards`（CASCADE 清流水）→ 删 folders → 按依赖顺序写 folders → cards → transactions。
- **MERGE**：保留现库，全部走 INSERT（id 清零让 SQLite 重分配），用 `oldId→newId` 映射重写 `cards.folderId` 与 `transactions.cardId`；不覆盖同 id 现有数据。

**FK 合法性校验（关键）**：写卡前校验 `folderId`——凡不在合法 folder 集合（REPLACE：本次 bundle 的 folders；MERGE：现库已有 + 本次新映射）里的，**置 null 并计数**（`cardsSkippedInvalidFolder`），而非抛异常回滚。避免「外键违反 → 整个事务 ROLLBACK → 用户合法数据被一起吞」。MERGE 中孤立流水（cardId 无映射）跳过并计入 `transactionsSkipped`，并检测与现库重名。

**健壮性**：所有写库包在 `database.withTransaction`（任一步失败/取消即 ROLLBACK）；`export`/`import` 协程 Job 存入 `activeJob` 可被 UI 取消；导出走 `encodeToStream` 流式序列化避免 OOM；跨设备恢复时统计 USER 卡面数提示用户重新上传图片。

---

## 13. 测试策略

- **JVM 单测**：JUnit4 + Robolectric 4.13 + Room in-memory + `kotlinx-coroutines-test`；`MainDispatcherRule` 切主调度器。`testOptions.isIncludeAndroidResources = true` 让 Robolectric 读资源。
- **现有测试**：`BackupRepositoryTest`（REPLACE/MERGE/校验语义）、`SettingsViewModelTest`、`ColorHexTest`、`MigrationTest`（v5→最新迁移 + 外键 SET NULL 回归）。
- **迁移测试不依赖导出 schema**（`exportSchema = false`）：手建旧版库 → 跑真实 `ALL_MIGRATIONS` → 强制 open 触发校验。`ALL_MIGRATIONS` 与生产共用一份，避免「测试漏注册某条迁移」。
- **CI**（`.github/workflows/`）：
  - `ci.yml`（CI）：所有分支 push/PR 跑 `ktlintCheck` + `:app:testDebugUnitTest`，传测试报告 artifact。
  - `build-apk.yml`（Build APK）：手动或 push main 时出 release APK（artifact `cardrecord-release-apk`）。
  - 两者都用 `gradle/actions/setup-gradle` 装真 Gradle（不碰仓库的 gradlew 桩），并运行时 `sed` 剥掉写死代理、`keytool` 现造 debug.keystore。
- 代码风格：ktlint（只扫 `**/kotlin/**`），CI 强制 0 violation。detekt 在 `RELEASE_CHECKLIST` 中列为目标但**插件尚未接入**，故 CI 暂不跑。

---

## 14. 构建与发布

- **Debug**：`./gradlew :app:assembleDebug`；**Release（debug 签名）**：`./gradlew :app:assembleRelease`。
- 出包优先走 CI（沙箱 android-sdk 残缺，难干净 `assembleRelease`）。
- 发版纪律见 `docs/RELEASE_CHECKLIST.md`，改动记 `docs/CHANGELOG.md`。

## 15. 已知约束与风险 (Risks)

> 详细复现/原因见 `CLAUDE.md`，此处只列风险条目。

- **仓库 `gradlew` 是沙箱专用桩**（`gradle-wrapper.jar` 不在仓库），他机/CI 直接 `./gradlew` 会挂——CI 已改用官方 setup-gradle 绕过。
- **`gradle.properties` 写死本地代理 `127.0.0.1:18080`**，非沙箱环境需剥掉（CI 运行时 `sed`，不提交回仓库）。
- **release 复用 debug 签名**（ADR-5），上架前必须换正式密钥。
- **本远程环境 tag push 被代理 403 拦截**，GitHub MCP 无 create_release/create_tag——打 tag + 发 Release 需用户本机 `gh` 收尾。
- **迁移脆弱性**：任何新增/修改列都要写逐字符匹配 Room schema 的迁移，并补 `MigrationTest` 用例（历史已多次因此踩坑，见第 9 节）。

---

## 16. 备选方案 (Alternatives Considered)

- **存储 `current_count` 字段**（vs ADR-1 派生）：写更快、查更简单，但双源一致性风险高——已被派生方案取代。
- **Hilt/Dagger DI**（vs ADR-6 手写容器）：编译期校验、可扩展，但对单模块小依赖图过重，且无法顺带承担全局事件总线职责。
- **自写 HSL 配色近似**（vs ADR-4 MaterialKolor）：少一个依赖，但配色不符 MD3、不协调——放弃。
- **流水表保留金额/商户/备注**：可做轻量记账，但与「不做记账」非目标冲突、且数据冗余于付款 App——v5→v6 已删。
- **WorkManager 定时续期** vs **启动时一次性续期**：本地无后台需求，启动跑一次（`while` 循环补推 N 年）足够，避免引入 WorkManager 复杂度。
