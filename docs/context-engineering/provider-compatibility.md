# Provider 与 Thinking

工作台使用 RikkaHub 的原生适配器，保留各协议的消息、工具和多模态表示。请求预览显示最终序列化正文，记录与发送共用同一份数据。

## 五个捕获边界

| 协议 | 实现文件 | Thinking 和工具表示 |
|---|---|---|
| Chat Completions | `ai/.../openai/ChatCompletionsAPI.kt` | `reasoning_content`、OpenRouter 原生 reasoning details、函数调用及结果 |
| Responses | `ai/.../openai/ResponseAPI.kt` | 原生 reasoning items、可见摘要、加密内容及工具项 |
| Anthropic Messages | `ai/.../claude/ClaudeProvider.kt` | thinking、签名、工具调用和结果，包括内部续轮 |
| Google generateContent | `ai/.../google/GoogleProvider.kt` | thought parts、thoughtSignature、函数交互及多模态 |
| Google Interactions | `ai/.../google/InteractionsAPI.kt` | 原生 thought / function steps 及签名 |

路径中的 `...` 为 `src/main/java/me/rerere/ai/provider/providers`。

## 手工编写思考

完整编辑器允许新增第一段或多段 Thinking。Chat Completions 会携带用户编写的普通思考；同组片段按顺序合并。自动产生的历史推理继续遵守 Provider 设置，显式工作台请求采用本次包含选择。

修改带签名或密文 metadata 的思考后，可以直接预览并发送。客户端保留原始 metadata 与当前编辑文本，由当前端点实际处理；成功返回就渲染，失败就显示 HTTP 状态码和错误正文。旧版本保存的已编辑思考同样可继续使用。

OpenRouter 原生 reasoning details 中的可见文字会使用当前编辑全文；签名、密文及其他字段继续保留。只有密文详情时，人工文本通过 `reasoning_content` 同时发送。Responses 保留 reasoning id 和 `encrypted_content`，并携带人工编辑的可见文本。

Google Interactions 会将无签名或来自其他协议的思考作为 thought step 发送其可见 summary。签名使用当前协议对应的 metadata 字段。

Thinking 是可访问或由你编写的思考文本。排除、转为正文和恢复原始内容仍由你选择。

## 原始请求编辑

消息、系统指令、模型名、参数、工具定义和原生签名/密文块都能在真实正文中调整。确认后的文本直接进入 HTTP entity，网络拦截器读回并核对 SHA-256。请求记录采用实际正文中的模型名；Google generateContent 的模型仍由 URL 端点指定。

流式模式通过会话设置切换，预览中的 `stream` 与当前响应解码器一致。本地工具按当前工具配置运行；模型返回的工作台工具调用先保存为待执行，再由你决定运行或提供结果。

每次序列化都会产生请求记录。同一轮重试、工具步骤和 Provider 内部续轮关联到同一 run，最终保留的回复归档一次。

## 工具与复制

工具调用与结果成对进入请求，历史缺失结果可执行或排除。静态复制历史工具不执行它；显式 Run 会重新检查当前工具授权与参数。

可以跨不同 Provider 的会话原样复制消息，保留部件结构和 metadata。目标消息与客户端工具调用建立新的身份，本地附件独立复制。你也可以选择文本方式，保留可访问内容与来源说明。

## 验证

五套生产序列化器均有本地 HTTP 传输测试。测试覆盖确认前不发送、确认正文与实际传输一致、SSE、签名编辑、跨协议思考、工具配对和实际错误返回，详见[验证记录](testing.md)。
