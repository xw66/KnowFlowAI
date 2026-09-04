# 会话持久化增量

新增 V11：conversation（所属用户、知识库、时间），chat_message（角色、正文、RUNNING / COMPLETED / FAILED / CANCELLED、模型与真实 usage、错误码、结束时间），message_citation（消息使用的全部证据、是否最终引用、原文和引用位置）。来源 ID 保留用于授权复核，不以外键阻止历史文档索引清理；引用记录从属于消息。

新增 ConversationService 与 ConversationController；已有答案接口增加可选 conversationId，不提供时自动创建。会话固定绑定知识库和用户，同一会话只允许一条 RUNNING 助手消息。同步响应头与 SSE metadata 返回 conversationId / messageId。开始时原子保存问题及 RUNNING 消息，成功持久化后才返回答案或 done；流中失败、取消保存部分正文，错误引用不会变为可用引用。

GET /api/conversations 与 GET /api/conversations/{id}/messages 提供本人分页读取。撤权后会话不可读；文档删除、换版或原文变化时，相关助手消息整体隐藏正文和引用，仍保留状态。所有输入证据均保存并重新检查，不只检查最终引用。超过五分钟仍 RUNNING 的消息在会话读取或续问时转为 FAILED / PROCESS_INTERRUPTED，避免崩溃遗留永久占用；后台统一对账后续完成。

验证：真实 MySQL 迁移和事务、本人隔离、跨库会话拒绝、并发生成约束、同步成功与失败、SSE 成功 / 取消 / 超时，以及撤权和来源失效后的历史内容隐藏。模型继续使用本地测试替身。

成功写入与引用更新在同一事务中，再次锁定知识库、复核权限和已存证据；任一引用更新不匹配会回滚 COMPLETED 状态。续问在会话行锁下使用当前读检查 RUNNING，避免 MySQL 默认可重复读快照漏掉刚提交的另一条请求。迟到的失败 / 取消只能更新 RUNNING，不能覆盖已提交终态。

流的 COMPLETED 表示生成和数据库提交已完成，不能保证客户端已收到最终数据包。数据库故障导致终态无法保存时记录 messageId 和异常类型，遗留 RUNNING 由恢复流程处理；不会因保存失败发出成功 done。问题与部分正文是持久化业务数据，来源全文留作权限复核，不输出到应用日志。
