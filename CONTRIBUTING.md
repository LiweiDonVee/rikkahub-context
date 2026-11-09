# 参与贡献

欢迎提交复现、改进建议、文档和 Pull Requests。围绕实际使用场景说明问题，保持原生 RikkaHub 交互。

## 提交问题

使用[本仓库 Issues](https://github.com/LiweiDonVee/rikkahub-context/issues)，写出操作步骤、预期结果、版本和所用协议。需要附请求片段时，先去掉 API Key 和个人信息。

## 修改代码

从 `main` 创建功能分支，按[构建指南](docs/context-engineering/build.md)运行相关测试。PR 说明触发场景、改变后的行为和验证结果。

主要约定：

- 编辑与执行分离，保存只保存。
- 加载中会话通过 ConversationSession/ChatService 写入。
- 请求预览来自真实 Provider 构建，确认字节和发送字节一致。
- Thinking、调用、结果、附件及原生 metadata 在编辑时保留身份。
- 使用原生 Material 3 Expressive、CardGroup、PagerTabs 和 TextArea。
- 预填充沿用原有初始化；模板修改与已有会话分别保存。

添加内容类型或 Provider 时，同时检查完整编辑器、编译、实际正文、来源、复制、恢复及文件生命周期。具体结构见[架构](docs/context-engineering/architecture.md)。
