# 信用卡管家 (Credit Card Tracker)

一款原生 Android 应用，用于追踪信用卡消费次数——某些信用卡要求刷满一定笔数才能免次年年费，银行 APP 通常不显示这个数据，本应用帮你手动追踪。

## 功能

- **多张卡片管理**：卡片名称、发卡行、卡号（脱敏）、主题色
- **消费记录**：每张卡可记录每笔消费（商户、金额、备注），自动同步笔数
- **笔数进度**：`x / y` 直观显示，还差几笔自动提示
- **横/竖两种卡面**：支持标准横版信用卡（Visa / MasterCard / JCB / UnionPay / Discover / QuickPass）和竖版（American Express / Diners Club）
- **8 个全球卡组织预设卡面**：可一键选用，亦可上传自定义卡图
- **下次年费结算日提醒**、卡片有效期记录
- **Material Design 3** 锋锐风格（expressive shapes、动态色、edge-to-edge）
- **数据持久化**：Room 本地数据库 + 自动备份

## 技术栈

- **语言**：Kotlin 2.1.20
- **UI**：Jetpack Compose（BOM 2025.05.00）+ Material 3
- **数据库**：Room 2.7.2（KAPT 注解处理）
- **图片加载**：Coil 3.4.0
- **导航**：Navigation Compose 2.9.0
- **构建**：AGP 8.12.0 + Gradle 8.14.4 + Java 17
- **最低支持**：Android 8.0 (API 26) / target SDK 36

## 项目结构

```
app/
├── src/main/java/com/example/creditcardtracker/
│   ├── data/
│   │   ├── local/          # Room：Entity、Dao、AppDatabase、Migration 1→2
│   │   ├── CreditCardRepository.kt
│   │   ├── AppContainer.kt # 手动 DI 容器（无 Hilt）
│   │   └── CardNetworkProvider.kt  # 8 个卡组织枚举
│   ├── ui/
│   │   ├── theme/          # Theme.kt / Shape.kt / Type.kt
│   │   ├── component/      # CreditCardVisual.kt
│   │   └── screen/         # CardList / CardDetail / CardEdit
│   ├── MainActivity.kt
│   └── CreditCardApp.kt
├── src/main/res/
│   ├── drawable/           # 8 个卡组织矢量卡面
│   └── ...
└── build.gradle.kts
```

## 构建

```bash
# Debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release APK（未签名）
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk

# 代码格式
./gradlew :app:ktlintFormat
./gradlew :app:ktlintCheck
```

## 数据迁移

当前数据库版本 `v2`（从 `v1` 起新增三列：`image_source_type`、`image_provider_key`、`card_orientation`）。
启动时自动执行 `MIGRATION_1_2`，失败时 fallback 到清库（兜底策略）。

## License

MIT