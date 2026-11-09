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

OpenRouter 的原生 reasoning details 独立保留，与新写的普通文本分别表示。签名和密文使用协议原始结构；修改签名对象后，可选择排除或显式转为正文。

Google Interactions 的无签名思考由原适配器省略。工作台会在确认前说明，并将这个确定的缺失记入请求记录。Claude、Responses 等接口对无签名手工思考的接收规则由具体端点决定；需要以文字参与上下文时可转为正文。

这里的 Thinking 是可访问的上下文文本，签名和加密块按原生协议保留。

## 原始请求编辑

消息、系统指令和参数都能在真实正文中调整。模型在会话设置中选择；`stream` 保持解码模式一致；工具定义保持当前授权集合。确认后的文本直接进入 HTTP entity，网络拦截器读回并核对 SHA-256。

每次序列化都会产生请求记录。同一轮重试、工具步骤和 Provider 内部续轮关联到同一 run，最终保留的回复归档一次。

## 工具与复制

工具调用与结果成对进入请求，历史缺失结果可执行或排除。静态复制历史工具不执行它；显式 Run 会重新检查当前工具授权与参数。

跨会话复制会检查签名、原生服务器工具和目标多模态支持。原样复制保留结构，文本方式保留可访问内容与来源说明。

## 验证

五套生产序列化器均有本地 HTTP 传输测试。测试覆盖确认前不发送、确认正文与实际传输一致、SSE、签名编辑、工具配对和省略反馈，详见[验证记录](testing.md)。
