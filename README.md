# 刷记（CardRecord）

刷记是一款本地优先的 Android 卡片消费笔数记录工具。它用于手动记录刷卡次数，并按年费统计窗口显示免年费进度。

## 主要功能

- 管理多张卡片与文件夹，并可区分借记卡、信用卡
- 记录有效期、下次年费结算日，以及信用卡的账单日和还款日
- 按“下次结算日前一个日历年”统计有效消费笔数
- 窗口尚未开始时不显示误导性的 `0 / N` 进度
- 支持纯色、卡组织预设和用户图片三种卡面，以及横版/竖版布局
- 长按删除前二次确认；删除卡片时同时删除其消费记录
- 简体中文与 English
- 通过系统文件选择器手动导入、导出包含 JSON 与自定义卡面的备份目录

## 隐私

应用没有账号和后端，数据保存在设备本地。系统云备份与设备迁移已关闭；只有用户主动导出时，数据才会离开应用私有存储。备份含明文卡片信息和自定义卡面，请妥善保管；成功导入后可删除导出的备份目录。

## 本地构建

需要 Android SDK 与 Java 17。仓库包含 Gradle Wrapper：

```bash
./gradlew :app:assembleDebug
```

正式发布使用仓库外保存的固定签名，流程见 [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md)。架构与数据约束见 [`docs/Design.md`](docs/Design.md)。

## 卡组织资源与商标

Visa、Mastercard、JCB、American Express、Diners Club 与 Discover 的图形路径参考 [Simple Icons](https://simpleicons.org/) 对应条目并转换为 Android VectorDrawable；UnionPay / 银联闪付图形由项目内维护。

品牌名称与标识归各自权利人所有，本项目仅将其用于识别卡组织。资源许可与品牌使用要求以来源项目和权利人的最新条款为准。
