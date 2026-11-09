# 维护与上游同步

公开 Fork：[LiweiDonVee/rikkahub-context](https://github.com/LiweiDonVee/rikkahub-context)。维护分支为 `main`；上游为 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)，当前共享基线 `9bc0fd65873c77b42e2743c9c7dba4d4594a6478`。

## 同步

```sh
git remote add upstream https://github.com/rikkahub/rikkahub.git
git fetch upstream
git switch main
git switch -c sync/upstream
git merge upstream/master
```

完成测试后通过 PR 合并回 `main`，保留共享历史。Spotless 基线为根 `build.gradle.kts` 中的固定上游提交。

## 重点集成点

- `ChatRequestFactory`：选择在原有条数/检查点后作用于副本，继续使用现有提示、记忆、工具与输入转换。
- 五套 Provider：`prepareRequest` 保持在最终正文和自定义合并之后，确认字符串直接用于 HTTP。
- `AgentLoop`：上下文请求使用新的输出槽，历史调用作为数据；人工工具继续由显式 Run/Continue 调度。
- `UIMessage` / `ContextPartDraft`：新增部件和重排保留来源身份，恢复、排除与签名状态正确映射。
- `ConversationSession`：所有加载中会话的写入仍经过 Session/ChatService。
- 原生预填充：保持 `presetMessages → newConversation → updateCurrentMessages`、ID/时间和字面内容；只为本地文件建立独立会话所有权。
- Room schema 33：请求/回复分块表、run 关联、迁移和级联删除。
- 附件：可见内容、原件、工具记录和回复归档的引用参与生命周期。

同步后优先复验确认字节、历史工具不执行、当前授权、并行工具结果、预填充初始化、附件归属和 Room 迁移。

## 发布

1. 在同一源代码提交上运行[构建和测试](build.md)。
2. 更新版本及 [CHANGELOG](../../CHANGELOG.md)，创建 `v<version>` 标签。
3. 在本仓库 Releases 上传 APK 和 SHA-256，写明签名类型与对应源代码版本。
4. 读回公开 release 与下载文件，核对版本、大小和摘要。

当前发布沿用已验证的 Debug 签名 APK，下载入口指向本 Fork。上游的定时 nightly 发布、私有签名/Firebase 配置和自动关闭 Issue 流程不参与本仓库发布。

保留 [LICENSE](../../LICENSE)、上游提交关系和第三方归属。
