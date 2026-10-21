# Y2YY Codex Android

原生 Kotlin / Jetpack Compose 客户端，通过 HTTPS 和 WebSocket 连接本仓库的
Codex Bridge。当前源码版本 **2.2.7**，最低 Android 9 / API 28，目标 API 35。

## 功能

- 项目与会话列表、历史聊天、本地加密缓存、断线后同步和会话执行状态。
- 模型、思考强度、标准 / 快速选择，记住上次设置。
- 默认跟进、显式排队、权威队列状态与单条撤回、停止执行。
- 工具调用折叠、图片与工具图片查看、相册、相机、文件和剪贴板。
- 原生审批、问题与 MCP 表单；会话重命名和项目移动。

这是独立的原生客户端，不是 WebView。服务端协议扩展位于本 Fork；不能假定未包含
这些扩展的上游 Bridge 与所有功能兼容。已验证的组合为本仓库 Bridge 0.3.13
定制版（协议 44）与 Codex 0.163.0。Codex 的模型、额度与认证由服务器管理。

## 本地构建

需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。
Gradle Wrapper 固定使用 Gradle 8.11.1；插件和依赖版本在 Gradle 文件中固定。

```sh
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Windows 使用 `gradlew.bat`。可通过 `ANDROID_HOME` 或本地 `local.properties` 设置
SDK 路径。Android Studio 可直接打开此目录。

没有私有配置时，Debug 使用 `config/access.example.json` 的占位地址，供编译、
单元测试和离线界面开发；它不会连接任何实际 Codex 服务。
调试 APK 位于 `app/build/outputs/apk/debug/`。

连接自己的服务器时，把配置复制到被 Git 忽略的私有目录：

```sh
mkdir -p .private
cp config/access.example.json .private/access.json
```

填写自己的 HTTPS 地址以及入口的 Basic Auth 用户名和密码。入口需要使用系统信任的
证书，并转发 Bridge 的 `/api/auth`、`/api/command`、`/api/events` 和文件预览接口；
WebSocket 也应转发。可以参考根目录的 [Bridge 安装说明](../docs/install.md)。
客户端保留 HTTPS 证书校验，并把认证头限制在配置的同源地址。

本客户端沿用个人自用设计：连接信息在构建时写入 APK，打开即可进入会话。
**APK 可被提取出这些信息**，因此自己的访问配置、签名密钥和含真实凭据的安装包
必须保密；开源仓库只包含占位模板，不提供含个人登录信息的 APK。

## 发布签名

Release 必须先配置 `.private/access.json`。使用自己的密钥签名：

```sh
cp config/signing.example.properties .private/signing.properties
# 将自己的 keystore 放到 .private/release.jks，并填写 signing.properties
./gradlew assembleRelease
```

未配置签名时会生成未签名 Release 包。升级已有安装必须沿用自己的签名密钥，
本仓库不包含任何个人签名文件。

## 验证范围

JVM 测试不连接真实服务器，使用合成数据与 MockWebServer。
本次公开源码已通过 166 项 JVM 测试（0 失败、0 跳过）、Debug 与设备测试包编译，
以及 Debug lint（0 错误、9 项既有警告）。这些检查使用占位配置，未连接真实服务器。
设备测试包编译成功不代表已在设备执行全部集成测试。

大多数界面测试使用合成数据；`NativeNavigationTest` 会启动应用并读取其配置的服务。
`LiveBridgeCompatibilityTest`、`ServiceTierIntegrationTest` 和 `LiveQuestionUiTest`
涉及真实会话操作，只有显式传入 `qaRoot` 才会启用，并校验服务端 `codex_home` 和
项目根目录。公开样例固定使用以下**隔离测试目录**：

```text
/tmp/codex-bridge-android-qa/codex-home
/tmp/codex-bridge-android-qa/chats
```

运行这些集成测试前，必须自行配置独立的 Bridge / Codex、测试项目和合成数据，
并将测试 APK 指向该隔离入口；不要指向正式服务。不传 `qaRoot` 时跳过这些测试，
跳过不能算作真实后端验收。测试中使用的 MCP / 媒体夹具需按测试约定另行准备，
本目录未附带可一键启动的服务器 QA 环境。

## 许可证

Android 客户端采用 [MIT License](LICENSE)。Bridge 保留根目录中原作者的
[MIT 许可证与版权声明](../LICENSE)。第三方库保持各自许可证。
