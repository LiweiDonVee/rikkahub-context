# 验证记录

`3.1.2-context.3` 的已执行结果，日期：2026-11-10。

| 检查 | 结果 |
|---|---|
| common JVM | 8 通过 |
| ai JVM | 320 通过 |
| app JVM | 510 通过 |
| JVM 合计 | **838 通过**，零失败、错误和跳过 |
| Android 集成/UI | **18 通过** |
| GitHub Debug | arm64-v8a、x86_64、universal APK 与测试 APK 构建成功 |
| 格式与签名 | Spotless、diff whitespace、APK 签名验证通过 |

Android 测试运行在 API 35 x86_64 模拟器。截图使用应用原生主题，当前发布 APK 使用 Debug 签名。

## 测试覆盖

| 测试类 | 核心行为 |
|---|---|
| `ContextDraftTest` / `ContextEngineeringTest` | 首段思考、全文/换行、恢复、身份、排除映射、旧签名编辑标记读取、工具配对 |
| `ChatCompletionsRequestMessageTest` | 多段思考、原生 details 当前全文替换与密文 metadata 保留 |
| `RequestControlTest` / `RequestReviewGateTest` | 正文校验、确认与取消、OCR/聊天阶段、敏感字段遮蔽和 JSON 差异 |
| `RequestCancellationTest` | 请求头前及阻塞正文读取中的取消 |
| `ContextWireIntegrationTest`（5 项） | 五个生产序列化器、本地 HTTP/SSE、签名/密文/模型编辑后的确认字节、3 MiB 分块、Interactions 无签名思考、20 组实际 HTTP 错误 |
| `ContextHistoryIntegrationTest`（6 项） | 静态保存不执行、跨协议原样复制、QuickJS 独立重跑、未知工具待执行、结果清空后独立审批、回复归档与文件 |
| `ContextPresetIntegrationTest`（2 项） | 原预填充初始化、普通首发、模板修改隔离、多个 Thinking 的传输、附件 A/B 删除隔离 |
| `ContextOcrIntegrationTest`（1 项） | 改写 OCR 请求与普通附件缓存隔离 |
| `Migration_32_33_Test`（1 项） | 旧消息 JSON 保留及新表迁移 |
| `ContextWorkbenchUiTest`（3 项） | 纯文本新增思考、预填充新增后再改正文、纯思考插入及保存无请求 |

这些测试与既有上游回归一起执行。工具测试实际运行本地 QuickJS，协议测试使用生产适配器和本地 HTTP 服务。

五种协议分别覆盖流式/非流式与 JSON/纯文本 HTTP 400 返回，共 20 组实际传输与错误报告。成功用例同时修改 Thinking、原生签名/密文和请求模型，核对确认正文与网络读回一致。

## 复现

```sh
./gradlew :common:testDebugUnitTest :ai:testDebugUnitTest :app:testGithubDebugUnitTest   :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest spotlessCheck
./gradlew :app:connectedGithubDebugAndroidTest
```

`ContextRealProviderTest` 提供外部端点的显式测试入口，检查正文、网关摘要、快照和回复。它从测试中继读取模型配置，认证可留在主机；测试参数名为 `contextRealProvider`。

外部接口用例按配置的端点和模型运行；协议字段与手工 Thinking 的表示见 [Provider 说明](provider-compatibility.md)。
