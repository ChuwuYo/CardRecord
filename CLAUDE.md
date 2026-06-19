# CLAUDE.md

写给在本仓库工作的 AI Agent / 新对话。读完这份再动手，能避开本项目几个非常坑的环境问题。

## 1. 项目是什么

- **刷记 / CardRecord**：本地优先的信用卡 / 卡片消费记录 Android App。
- 包名 `com.shuaji.cards`；当前 **v1.5.5 / versionCode 22**（见 `app/build.gradle.kts`）。
- 纯本地，无后端账号；数据存设备本地（Room + DataStore），支持导入/导出备份。

## 2. 技术栈

- Kotlin + **Jetpack Compose** + **Material 3**（含 Material You 动态取色）。
- **Room**（DB version 7，见 `AppDatabase.kt`）做持久化；**DataStore Preferences** 存设置。
- 手写轻量 DI：`data/AppContainer.kt` + `ShuajiApplication`，ViewModel 走 `*ViewModelFactory` / `ViewModelFactories.kt`。
- **i18n**：简体中文 + English，`AppCompatDelegate.setApplicationLocales` 做应用内语言切换。
- 第三方：`skydoves/colorpicker-compose`、`materialkolor`、`coil3`。

## 3. ⚠️ 环境/构建大坑（最重要，先看这节）

这些是本会话踩出来的，务必先知道：

1. **`gradlew` 是假桩**：仓库里的 `gradlew` 只有一行 `exec /root/.local/share/mise/installs/gradle/8.14.4/.../gradle`，**`gradle-wrapper.jar` 不在仓库里**。它只在 Claude 沙箱容器里能跑（那条 mise 路径存在）。**别的机器 / CI 上 `./gradlew` 会直接挂**。
   - 沙箱里要手动跑构建：用系统 gradle `/opt/gradle/bin/gradle`（8.14.3）。
2. **`gradle.properties` 写死了代理** `127.0.0.1:18080`（沙箱出口代理）。在 GitHub runner 等无该代理的环境会让依赖下载卡死——CI 里已用 sed 在运行时剥掉，**不要把这几行删出仓库**（删了沙箱本地构建会坏）。
3. **沙箱里的 `android-sdk/` 是残缺手攒包**：platform 缺 `framework.aidl`/`data/`、只有 build-tools 35（compileSdk=36）。沙箱里很难干净 `assembleRelease`。**要出 APK 优先走 CI**（见下）。
4. **release 复用 debug 签名**：`signingConfigs.releaseDebugSigned` 读 `~/.android/debug.keystore`（凭据全是 `android`/`androiddebugkey`）。不是生产签名，仅为可安装。CI 里用 `keytool` 现造一个。

## 4. CI / 出包

- **两个 workflow**（`.github/workflows/`）：
  - `ci.yml`（**CI**）：所有分支 push / PR 跑 `ktlintCheck` + `:app:testDebugUnitTest`，
    concurrency 取消同分支旧运行，上传测试报告 artifact。**这是验证关**——改完推分支即自动跑。
  - `build-apk.yml`（**Build APK**）：见下，只出 APK。
- **Build APK** 触发：手动 `workflow_dispatch` 或 push 到 `main`。
- 它**不**用仓库的假 gradlew，而是用 `gradle/actions/setup-gradle@v4` 装真 Gradle 8.14.4，再 `gradle :app:assembleRelease`。
- 产物：artifact **`cardrecord-release-apk`**，在运行页 Artifacts 下载（保留 90 天）。
- 已验证可成功出包。日志里 `Node 20 deprecated` 和 `git exit 128`（setup-gradle 的 dependency-graph 探测）都是**无害 warning**。

## 5. Git / 发布限制（本远程环境）

- **branch push 正常**；**tag push 被代理 403 拦截**（`refs/tags/*`）。
- GitHub MCP **没有 create_release / create_tag** 工具，只有 get/list。
- 结论：在本环境**无法**自动完成「打 tag + 发 GitHub Release」。代码合并到 main 没问题；tag 与 Release 需用户在本机用 `gh` 收尾（见 `docs/RELEASE_CHECKLIST.md`）。

## 6. 代码约定

- 注释用**中文**，跟随现有风格（密度、口吻）。
- lint：`ktlint`（`./gradlew ktlintCheck`，只扫 `**/kotlin/**`）+ `detekt`。提交前应 0 violation。
- **所有用户可见文案走资源**：`res/values/strings.xml` 与 `res/values-en/strings.xml` 必须同步；禁止 Composable 里写死中文字符串。
- 主题色走 `DefaultBrandPrimary` 常量 / 资源 ID，不内联十六进制。
- 发版纪律见 `docs/RELEASE_CHECKLIST.md`；改动记到 `docs/CHANGELOG.md`。

## 7. 测试

- 单测用 **JUnit4 + Robolectric 4.13 + Room in-memory + kotlinx-coroutines-test**；`MainDispatcherRule` 切主调度器。
- Robolectric 的 `android-all-instrumented` SDK 默认走 Sonatype 在国内不稳，`gradle.properties` 里配了 aliyun 镜像 + 可用 `ROBOLECTRIC_*` 环境变量走本地仓库/离线（见 `app/build.gradle.kts` testOptions 注释）。
- 现有测试：`BackupRepositoryTest`、`SettingsViewModelTest`、`ColorHexTest`、
  `MigrationTest`（Room v5→最新迁移 + 外键 SET NULL 回归）、`CardRepositoryTest`
  （刷卡派生计数 / 重置 / 年费自动续期）。
- 迁移测试不依赖导出 schema（本项目 `exportSchema=false`）：手建旧版库 → 跑
  `AppDatabase.ALL_MIGRATIONS` → 强制 open 触发校验。新增/改列必须补迁移 + `MigrationTest` 用例。

## 8. 源码地图

```
app/src/main/java/com/shuaji/cards/
├─ MainActivity.kt / ShuajiApplication.kt        入口、Application 容器
├─ data/
│  ├─ AppContainer.kt                            手写 DI 容器
│  ├─ CardRepository.kt / SettingsRepository.kt  仓库层
│  ├─ CardNetworkProvider.kt                     卡组织图标/网络
│  ├─ backup/ (BackupModels, BackupRepository)   导入导出
│  └─ local/ (AppDatabase, *Dao, *Entity)        Room
└─ ui/
   ├─ ShuajiApp.kt / AppLanguage.kt / ViewModelFactories.kt
   ├─ component/ (CardListItem, CardVisual, ModernColorPicker)
   ├─ screen/ (CardList/CardDetail/CardEdit/CardFolder/Settings + 各 ViewModel)
   └─ theme/ (Theme, Type, Shape, ColorHex)
```

## 9. 当前进行中的工作

- 分支 `claude/project-quality-and-docs` 正在做「全项目质量提升 + 文档化」。
- **架构/设计/决策见 `docs/Design.md`**（WHY 与架构取舍，ADR）。
- **进度看板与计划见 `docs/PLAN_quality_pass.md`**（含问题清单与「自动压缩前进度记忆」附录）。续作时先读它。
