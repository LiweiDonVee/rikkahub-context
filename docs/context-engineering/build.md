# 构建与开发

## 环境

| 工具 | 本版本配置 |
|---|---|
| JDK | 已验证 Android Studio JBR 25；Java/Kotlin 目标为 17 |
| Gradle | 仓库 Wrapper 9.6.0 |
| Android SDK | platforms 37.0 / 37.2，Build Tools 37.0.0 |
| Web UI | Node.js 22 与 pnpm 11；使用锁文件安装 |

用 Android Studio 配置 SDK，或设置 `ANDROID_HOME`。Debug 构建使用项目默认调试签名，不需要 Firebase 项目文件。

## 获取与构建

```sh
git clone https://github.com/LiweiDonVee/rikkahub-context.git
cd rikkahub-context
pnpm --dir web-ui install --frozen-lockfile
./gradlew assembleGithubDebug
```

Windows 使用 `gradlew.bat`。Gradle 会构建现有 web-ui 并打包进应用。

APK 输出位于 `app/build/outputs/apk/github/debug/`，包括 arm64-v8a、x86_64 和 universal。最低 Android API 为 26。

## 测试与格式

```sh
./gradlew :common:testDebugUnitTest :ai:testDebugUnitTest :app:testGithubDebugUnitTest
./gradlew :app:assembleGithubDebugAndroidTest
./gradlew spotlessCheck
```

连接测试模拟器或设备后：

```sh
./gradlew :app:connectedGithubDebugAndroidTest
```

外部接口用例使用显式的 `contextRealProvider` 配置；普通本地运行会跳过该可选用例。主要本地用例与结果见[验证记录](testing.md)。

Spotless 的格式基线固定为 `9bc0fd65`，与远程名称无关。完整 clone 会包含这个共享上游提交；浅克隆可先执行：

```sh
git fetch --unshallow
```

## 包名与签名

- Namespace：`me.rerere.rikkahub`。
- 独立 applicationId：`me.rerere.rikkahub.context`。
- Debug applicationId：`me.rerere.rikkahub.context.debug`。
- 当前版本：`3.1.2-context.4`，versionCode 198。

当前公开 APK 使用 Debug 签名。Release 构建可按 `app/build.gradle.kts` 的 signingConfig，在本机 `local.properties` 配置 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，然后运行：

```sh
./gradlew assembleGithubRelease
```

签名配置留在本机。`UPDATE_FEED_URL` 默认空，版本下载通过本 Fork 的 Releases 提供。
