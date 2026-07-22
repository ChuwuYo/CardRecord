# Release Checklist

每次发布按顺序核对；任一项失败就停止发布。

## 1. 代码与数据

- [ ] `./gradlew ktlintCheck :app:testDebugUnitTest :app:lintDebug :app:lintRelease` 通过
- [ ] 简中/英文资源 key 与格式化参数一致，无用户可见硬编码文案
- [ ] Room schema、迁移测试与备份 schema 3 已核对；零图片/有图片目录布局、图片恢复及 schema 1/2 旧 JSON 导入均通过
- [ ] main 源码没有遗留 TODO/FIXME、Debug 入口、临时日志或生成产物
- [ ] `docs/CHANGELOG.md` 的 Unreleased 已准确描述用户可感知变化

## 2. 版本与签名

- [ ] `versionCode` 递增，`versionName` 按语义化版本更新
- [ ] 正式密钥存在仓库外的安全介质，并有可恢复的离线加密备份
- [ ] `CARDRECORD_RELEASE_*` 对应的秘密值与密钥材料只从安全环境注入，没有写入脚本、文档或 Git 历史；变量名可以公开
- [ ] `apksigner verify --print-certs <release.apk>` 与仓库外可信记录及上一正式包一致

本地需要验证 Release 时，将 `CARDRECORD_RELEASE_STORE_FILE` 指向 `<secure-keystore-path>`，其余密码从系统安全存储读取。不要在命令历史或文档中展开真实值。

## 3. 构建与发布

- [ ] GitHub 的统一 CI 工作流在目标 `main` 提交上依次通过 `verify`、`build-sign` 与 `publish`
- [ ] 非 `main` 运行没有接触正式签名 Secrets
- [ ] 构建与发布阶段核对的 `GITHUB_SHA` 都是 `main` 当前头，旧运行重跑已被拒绝
- [ ] Release APK 的版本号、签名与 SHA-256 已核对
- [ ] `apk-latest` 只含一个 `app-release.apk`，资产、tag 提交和下载摘要均指向同一构建
- [ ] 正式版本 tag 与 GitHub Release 使用 `v<versionName>`，说明来自同一 Changelog 条目

## 4. 签名恢复预案

GitHub Secrets 不是密钥备份。若原签名无法恢复，新包不能覆盖旧安装；发布说明必须明确提示用户先在旧版导出备份，再卸载、安装新包并导入，避免本地数据随卸载丢失。
