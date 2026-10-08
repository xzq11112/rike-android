# 构建、测试与签名

当前代码为日课 Android 0.6.7，`versionCode` 为 20。公开仓库根目录就是 Android 工程，不需要网页版、Cloudflare 或 ChatGPT 运行环境。网页版保持私有，不属于本仓库的 AGPL-3.0-only 授权范围。

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK Platform | 35 |
| Android SDK Build Tools | 35.0.0 |
| Gradle wrapper | 8.11.1 |
| Android Gradle Plugin | 8.9.2 |
| 最低 / 编译 / 目标 Android API | 26 / 35 / 35 |

用 Android Studio 打开仓库根目录，并安装 SDK 35。命令行可配置本机 `JAVA_HOME`、`ANDROID_HOME`，或用 `local.properties` 指定本机 SDK 路径；这个文件不提交 Git。

首次构建需联网下载公开工具和 Maven 依赖，不需要作者的账号凭证、业务服务器或生产签名密钥。记录与备份功能离线运行；仅用户手动检查更新时需要网络。

## 测试与构建

在仓库根目录运行：

```bash
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintRelease :app:assembleDebug :app:assembleRelease
```

Windows 使用同参数的 `gradlew.bat`。请在没有真实资料的测试环境运行开发版和回归测试。

| 产物 | 用途 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 可调试测试包，包名 `app.rike.offline.preview.audit`，只使用虚构资料 |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 正式构建，包名 `app.rike.offline`，本机签名后才可安装 |

流水线配置在 [`.github/workflows/android-offline.yml`](./.github/workflows/android-offline.yml)。验证包含 Debug/Release 测试、Release lint，以及最终 Release APK 的手动检查更新所需 `INTERNET` 权限和无 `debuggable` 标记检查。更新检查测试覆盖请求内容、超时、禁止重定向、响应大小、版本比较、失败重试及生命周期。已执行检查与尚未完成的设备验收见 [VALIDATION.md](./VALIDATION.md)。

## 签名与更新

签名脚本是公开代码，原作者的私钥、保护密码和生产签名配置不在仓库或 fork 中。发布者在自己的电脑创建、导入或复用长期签名密钥；Windows 双击流程见 [release-windows/README.md](./release-windows/README.md)。流程还需要对应版本的未签名 APK 和官方 `apksigner`。

密钥保存到仓库之外，保护密码每次通过工具交互输入；不上传至 AI、GitHub 或 CI。发布者自行保管密钥、密码、别名与证书指纹。签名密钥授权应用更新，和资料库密码、恢复密钥是不同的东西。

更新现有安装需要相同包名、兼容资料格式、更高 `versionCode` 和同一签名身份。fork 的新签名不能覆盖原作者签名的已安装版本；独立发行建议修改 `app/build.gradle` 中的 `applicationId`，使用自己的包名，再通过使用者自行导出、验证的兼容备份迁移资料。不要先卸载唯一有数据的旧应用来尝试解决签名冲突。

使用者安装发布者已经签好的 APK，无需获取签名私钥。维护加密、资料格式、生命周期和文件选择流程前，先阅读 [SECURITY.md](./SECURITY.md)，并运行上述回归检查。问题报告只使用虚构测试资料。
