# 刷记 Release Notes

## v1.5.2 (2026-06-17)

### 修复

- **自定义主题色现在会作用于整个界面**。此前选择自定义颜色时只有按钮和强调文字会变色，背景与卡面仍停留在默认的蓝绿色调；现已修复，背景、各级表面与容器颜色都会随所选颜色一同协调变化。

### 优化

- 自定义配色改用 Material 3 官方动态取色方案生成整套颜色，观感与系统动态色保持一致、过渡更自然。
- 内部稳定性与代码质量改进。

## v1.5.1 (2026-06-17)

- 卡组织图标替换为官方规范 SVG（Visa / MasterCard / JCB / UnionPay / American Express / Diners Club / Discover）
- 修复卡组织图标在卡片上的渲染变形与比例问题
- 修复 Android SDK Color 函数不可用导致的编译错误
- 主题色 HSL 计算改用纯 Kotlin 实现，解除对 `android.graphics.Color` 的依赖
- `SettingsScreen` 补充缺失 import，保证 release 构建链路完整

## v1.5.0

- 新增信用卡年费结算日提醒
- 新增卡片有效期记录
- 支持横版 / 竖版两种卡面布局
- 引入 skydoves/colorpicker-compose 现代化调色板（HSV 色环 + 亮度/透明度/饱和度滑块）
- 数据库升级至 v2，新增 `image_source_type` / `image_provider_key` / `card_orientation` 三列

## v1.4.0

- 多卡片管理、消费记录、笔数进度基础功能上线
- 本地 Room 数据库 + 自动备份
- Material Design 3 风格 UI
