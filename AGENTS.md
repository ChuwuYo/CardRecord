# AGENTS.md

本文件是仓库内 AI Agent / 新对话的唯一开发规范入口。开始修改前先核对真实源码和构建配置；文档与代码冲突时，以当前代码为准并同步修正文档。

## 项目与架构

- **刷记 / CardRecord**：本地优先的 Android 卡片消费笔数记录应用。
- 包名 `com.shuaji.cards`；当前 `v1.6.0 / versionCode 24`。
- Kotlin + Jetpack Compose + Material 3；Room v8 存业务数据并用 KSP 生成代码，DataStore 存设置。
- 无账号、后端或自动云同步；用户通过 SAF 主动导入/导出 JSON 备份，系统云备份和设备迁移关闭。
- 手写依赖容器 `AppContainer`；Screen → ViewModel → Repository → DAO，Repository 仍会向 ViewModel 暴露部分 Entity / 投影，不是完整领域模型隔离层。

## 环境与构建

- 始终使用仓库的 `./gradlew`；Java 17，compile/target SDK 36。
- `gradle.properties` 可能包含本机代理配置。代理不可用时只在命令行临时覆盖，不为适配单台机器提交环境专属改动。
- 正式签名只通过 `CARDRECORD_RELEASE_*` 环境变量或 GitHub Secrets 注入；密钥、密码和本机路径不得进入 Git。变量缺失时 Release 必须失败，禁止回退到 debug 签名。
- 常用验证：

```bash
./gradlew ktlintCheck :app:testDebugUnitTest :app:lintDebug
```

## 设计与代码规范

- 从用户体验、数据不变量和失败语义出发，先修根因再抽象。只有出现真实重复规则或变化轴时才增加抽象，避免包装层和“架构感”代码。
- 保持职责单一、依赖方向清楚、同一概念一个可测试真源；主动删除确认无调用的死代码、重复实现、错误或广告式注释。
- 注释用中文，解释 WHY、边界或历史约束，不复述代码。
- 所有用户可见文案进入 `values/strings.xml` 与 `values-en/strings.xml`，数量文案使用 `plurals`；两种语言的 key、quantity 和格式化参数必须同步。
- 不用 `Any`、星投影、`!!`、未校验强转或魔法字符串规避建模。优先明确类型、空安全、泛型、enum / sealed class / value object 和穷尽 `when`；框架边界例外必须隔离在最小范围。
- 数据库、DataStore、JSON 中的枚举使用显式稳定 key；集中解码并定义未知值回退，禁止把 `enum.name` / `valueOf` 当长期协议。
- 协程宽捕获和 `runCatching` 不得吞掉 `CancellationException`；只在 IO、序列化、数据库或 UI 错误映射边界使用。

## 业务与数据不变量

- `colorArgb` 是单张卡底色唯一真源；卡组织只决定品牌标识，卡面样式决定徽标、水印和光环。用户图片只显示原图与文字。
- 卡组织与卡面样式是独立维度；用户图片可暂时隐藏卡组织选择，但不能清除已保存的选择。
- 卡类型使用稳定三态 `UNSPECIFIED / DEBIT / CREDIT`；旧数据保持未选择，不能推断为信用卡。账单日、还款日仅信用卡可保存，均为可空的 1..31 日。
- 下次结算日是日历日期令牌。有效窗口为 `[上一年同月日, 下次结算日)`，不是固定 365 天；窗口未开始时不计数也不允许记一笔。
- 卡片有效期按用户本地日历日判断，进入次日后才算过期。
- 禁止用 SQLite `REPLACE` 写被外键引用的父表。新卡可 `@Upsert`；编辑必须 `@Update` 并在目标已不存在时失败，不能复活已删除卡片。删除卡片必须二次确认并永久级联删除流水。
- Room 升级必须显式迁移并 fail closed；新增或改列同步导出 `app/schemas/` 并补 `MigrationTest`，不得用 destructive fallback 掩盖缺失迁移。
- 备份 schema 当前导出 `2`，并兼容导入 `1`。任何字段、ID、归一化或写入路径变更都要验证导出、REPLACE、MERGE 与旧备份兼容；多表导出取同一事务快照，输出必须不超过本版本导入上限，导入写入保持单事务。

## 测试、审查与交付

- 测试使用 JUnit4、Robolectric、Room in-memory 与 `kotlinx-coroutines-test`。静态编译、单测、Lint、构建、模拟器/真机和云端 CI 是不同验证层级，只报告实际完成的层级。
- `ci.yml` 对 push / PR 执行格式、单测和 Debug Lint；`build-apk.yml` 只允许 `main` 接触正式签名并更新滚动测试包。发布纪律见 `docs/RELEASE_CHECKLIST.md`。
- 并行代理只能领取互不重叠的文件或只读审查。整合后主代理必须复查完整 diff，并分别进行规范审查与需求审查。
- 文档必须对应真实代码并明确当前状态；完成项及时收口。优先合并或删除重复、过期文档，不为一次性过程创建长期文档。
- 临时报告、截图、专用 Debug 入口、APK 与仓库构建缓存只保留到最终交叉审查结束；清理后不要再次生成一批待删除产物。
- 改动记入 `docs/CHANGELOG.md`；架构与长期不变量只维护在 `docs/Design.md`。

## 代码入口

```text
app/src/main/java/com/shuaji/cards/
├─ core/                 跨层通用并发原语
├─ data/                 Repository、周期/日期、备份
│  └─ local/             Room Entity、DAO、AppDatabase、Migration
└─ ui/
   ├─ component/         卡面与通用组件
   ├─ screen/            Screen 与 ViewModel
   └─ theme/             主题、字体、形状与颜色
```
