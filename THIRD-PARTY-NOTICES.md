# 第三方组件与许可

主项目采用 AGPL-3.0-only。该许可仅适用于有权授权的项目代码；依赖、仓库所带工具以及发布包所带组件，继续遵循各自的许可和版权声明，不能统一改为主项目许可证。

| 组件 | 用途与许可 | 许可声明和官方来源 |
| --- | --- | --- |
| `org.bouncycastle:bcprov-jdk18on:1.83` | 唯一正式外部运行时依赖，用于 Argon2id；Bouncy Castle License（MIT 风格许可） | 依赖 JAR 中的 `org/bouncycastle/LICENSE.class`；[该版本许可全文源码](https://github.com/bcgit/bc-java/blob/r1rv83/core/src/main/java/org/bouncycastle/LICENSE.java) |
| Gradle wrapper 8.11.1 | 构建入口；Apache-2.0 | `gradle/wrapper/gradle-wrapper.jar` 内的 `META-INF/LICENSE`；[该版本上游许可](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE) |
| Android SDK Build Tools 35.0.0 `apksigner` | Windows 分发包的本机签名工具；Apache-2.0 | 已有分发包中的 `LICENSE-apksigner.txt` 与 `THIRD-PARTY-NOTICES.txt`；[官方源码](https://android.googlesource.com/platform/tools/apksig/)和[工具文档](https://developer.android.com/tools/apksigner) |

Bouncy Castle 版权声明为 Copyright (c) 2000–2023 The Legion Of The Bouncy Castle Inc.；`apksigner` 为 Copyright (C) 2016 The Android Open Source Project。再分发这些组件时，须一并保留其适用的版权与许可声明。

JUnit 4.13.2、Robolectric 4.14.1 和 `org.json` 20240303 仅用于测试，不进入正式 APK；各自遵循其锁定版本的上游许可。Java 与 Android SDK 由构建者单独安装，不随源码仓库分发。

本源码仓库不含 `apksigner.jar` 或生产签名私钥。提供或再分发 Windows 签名套件时，应保留工具所附许可文件；签名脚本与日课 APK 是独立于该第三方工具的项目代码。
