# Release Checklist（v1.3.6 起模板）

每次发版前按下面顺序走一遍。0/1 都是 "卡住" 信号，停下来修。

## 1. 代码侧
- [ ] `compileDebugKotlin` 0 error / 0 warning（warning 必须 0 写出来才算完）
- [ ] `./gradlew ktlintCheck` 通过（0 violation）
- [ ] `./gradlew lint` 通过（`lint { abortOnError = false }`，产物里没有 Error 级项）
- [ ] `./gradlew detekt` 通过（首次接入见 `config/detekt/detekt.yml`）
- [ ] 没有任何 `// FIXME`、`// TODO` 留在 main 分支
- [ ] 没有 `printStackTrace` / `Log.d` 留作 debug 用途
- [ ] 没有未使用的 import / 字段 / 参数（"留着" 违反第一性原理）

## 2. i18n / 架构
- [ ] `res/values/strings.xml` 覆盖所有用户可见文案
- [ ] 没有 `Text("中文字符串")` 出现在 Composable
- [ ] 主题色走 `DefaultBrandPrimary` / `DefaultBrandPrimaryDark` 常量，不写死十六进制
- [ ] 卡组织 / 文件夹颜色走资源 ID，不内联到 Composable

## 3. 版本
- [ ] `versionCode` +1
- [ ] `versionName` 按 semver 升（修 bug → patch，加功能 → minor）
- [ ] `CHANGELOG.md` 当节写完（一句话能说清：修了什么 / 加了什么 / 改了什么）

## 4. 构建
- [ ] 重新确认 `~/.android/debug.keystore` 存在（CI 上重生成用 `keytool`）
- [ ] `./gradlew assembleRelease` 产物在 `app/build/outputs/apk/release/app-release.apk`
- [ ] `aapt dump badging app-release.apk | grep versionName/versionCode` 验证值
- [ ] `apksigner verify --print-certs app-release.apk` 确认签名指纹与上一版一致

## 5. 发布
- [ ] commit + tag `v<versionName>`（annotated tag）
- [ ] `git push origin main --tags`
- [ ] `gh release create v<versionName> app-release.apk --title "v<versionName>" --notes "..."`（notes 跟 `CHANGELOG.md` 同节）
- [ ] Release 标题前缀保持 `v`（与历史一致）

## 6. 签名回滚预案
如果历史 release 用了真正的发布密钥，但密钥不可恢复：
- 在 `docs/RELEASE_NOTES.md` 顶部加 "⚠️ 签名变更" 段落
- 保留旧 `app-release.apk` 的 sha256
- 在 `README.md` "下载" 表格中标注签名变更
