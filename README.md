# 刷记（CardRecord）

**简体中文** | [English](README.en.md)

刷记是一款本地优先的 Android 应用，用来记录每张卡的刷卡笔数，看清离免年费还差多少。

## 功能

- 管理多张借记卡和信用卡，可用文件夹分组
- 记录有效期、下次年费结算日，以及信用卡的账单日和还款日
- 按结算日前一年的统计窗口累计消费笔数，显示免年费进度
- 距结算日约 30 天和 10 天时提醒当前进度
- 卡面可选纯色、卡组织预设或自己的图片，支持横版和竖版
- 导入、导出备份，自定义卡面一并保存

## 下载

需要 Android 8.0 及以上。最新安装包见 [apk-latest](https://github.com/ChuwuYo/CardRecord/releases/tag/apk-latest)，更新内容见 [更新日志](docs/CHANGELOG.md)。

## 隐私

没有账号，也没有服务器，数据只存在你的手机上。只有你主动导出备份时，数据才会离开应用。备份里是未加密的卡片信息和卡面图片，请妥善保管；导入成功后可以删掉备份。

## 本地构建

需要 Android SDK 与 Java 17：

```bash
./gradlew :app:assembleDebug
```

正式签名与发布流程见 [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md)，架构与数据约束见 [`docs/Design.md`](docs/Design.md)。

## 卡组织资源与商标

Visa、Mastercard、JCB、American Express、Diners Club 与 Discover 的图形路径参考 [Simple Icons](https://simpleicons.org/) 对应条目并转换为 Android VectorDrawable；UnionPay / 银联闪付图形由项目内维护。

品牌名称与标识归各自权利人所有，本项目仅将其用于识别卡组织。资源许可与品牌使用要求以来源项目和权利人的最新条款为准。
