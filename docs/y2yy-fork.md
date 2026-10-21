# Y2YY Fork

[English upstream documentation](../README.md#codex-app-server-webui-for-homelab-and-nas) ·
[Android build guide](../android/README.md)

本 Fork 基于 `hitsmaxft/codex-bridge`，当前合并的上游版本为 **0.3.13**，
上游基线提交为 `6c93e3bb542a92943e124df7b7ca9a951598bf3a`。
Bridge 和原生 Android 是两个客户端组件，共享同一套服务端会话与历史。

## 定制改动

- **输入与跟进：**Enter 发送、Shift+Enter 换行，保留中文输入法组合输入；
  支持粘贴图片，跟进模式不会被空闲刷新重置。
- **队列一致性：**区分真正排队和已提交回执，按提交标识对齐历史；重放失败保留
  错误结果，避免把未确认的提交当成成功，也避免重复提交。
- **模型设置：**标准 / 快速切换、偏好持久化、原生设置回读；模型目录冷启动时
  先加载能力再校验组合。快速模式是否可用以及实际耗用由 Codex 账号决定。
- **审批与提问：**展示服务端待审批命令并回传决定，适配阻塞式 Codex / MCP
  请求；澄清问题随所属轮次结束而失效，历史回复转换为可读内容。
- **项目与工作树：**安全创建服务器项目、识别已注册项目、会话移动与原生目录
  回读确认，兼容旧会话的项目归属。
- **历史与媒体：**工具身份对齐、历史叠加去重、懒加载索引修复，以及 SDK 工具
  图片在缺少先行助手消息时的历史宿主恢复。
- **执行状态：**启动回执和全局活动快照驱动侧栏更新；包含按会话的轮次 ID 和
  序号，支持统筹派单后的后台会话状态同步。

原生 Android 的具体功能和构建入口见 [android/README.md](../android/README.md)。
移动端版本号独立于 Bridge，目前是 **2.2.6**。

## 兼容性与验证

当前验证组合为本 Fork 的 Bridge 0.3.13（协议 44）、Codex 0.163.0 和
Android 2.2.6。不能据此推断未来 Codex、上游 Bridge 或所有部署形态都兼容。
Bridge 的构建与验证顺序见 [AGENTS.md](../AGENTS.md)，
消息同步约定见 [流式界面文档](web-ui-streaming.md)。

这些 Bridge 定制改动在公开前通过了 238 项 JavaScript 测试、336 项 Rust 测试
（另有 2 项既有忽略项）。公开副本与已验证 Bridge 源码逐提交比对：28 条定制提交的
文件树与合并关系保持一致，提交身份统一为公开的 GitHub noreply 身份。

Android 发布源码的 JVM 与构建检查使用占位配置，不接触真实服务器。
真实集成测试必须显式启用并指向隔离服务，详见 Android 构建指南。

## 后续维护

`main` 保存上游改动与本 Fork 定制功能。建议保留以下远程：

```sh
git remote add upstream https://github.com/hitsmaxft/codex-bridge.git
git fetch upstream
git switch -c maintenance/upstream-update
# 先检查和合并，再运行文档中的测试；不要直接覆盖本 Fork 的定制改动。
git merge upstream/main
```

生产服务配置、认证和会话数据应独立存放，不随源码更新或开源提交移动。
公开构建使用自己的配置与签名，仓库不分发任何个人连接信息。

## 版权与许可证

上游版权和 [MIT 许可证](../LICENSE) 保持原样。
Y2YY 定制改动和原生 Android 源码同样按 MIT 发布；
Android 版权声明见 [android/LICENSE](../android/LICENSE)。

自动同步和上游合并规则见 [维护流程](../tools/maintenance/README.md)。
