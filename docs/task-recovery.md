# 过期任务恢复

管理员接口 `POST /api/admin/reconcile/stale-tasks` 必须提交任务 ID；不再支持无参数全局恢复。最多 100 个正整数，重复 ID 只处理一次。

默认只预览：

```json
{"taskIds":[123,124]}
```

实际执行：

```json
{"taskIds":[123,124],"dryRun":false}
```

响应包含 `dryRun`、`changedTasks` 和逐项 `tasks`（`taskId`、`outcome`、`reason`、`nextStatus`）。预览结果不是执行许可快照；执行会重新读取并锁定文档和任务。

- `PREVIEW`：可以处理，但未写入。
- `RECOVERED`：已转入 `RETRY_WAIT` 或 `FAILED`。
- `SKIPPED`：返回 `NOT_FOUND`、`DOCUMENT_DELETED`、`OLD_VERSION`、`NOT_PROCESSING`、`LEASE_NOT_EXPIRED` 或 `UNSUPPORTED_STAGE`。

解析阶段使用 `attempts`，向量阶段使用 `vector_attempts`，耗尽时分别记录 `RETRY_EXHAUSTED` 和 `VECTOR_RETRY_EXHAUSTED`。失败文档若存在激活版本则保持 READY，否则转入 FAILED；不切换激活版本，不删除外部索引。状态写入递增缓存版本，事务提交后失效旧缓存。

Worker 已能自动接管过期租约，通常无需人工恢复。本接口只用于明确范围的运维操作；MySQL 对账计数不代表已经检查了 Qdrant、Lucene 和文件的真实完整性。

验证使用独立 MySQL Testcontainers，覆盖默认预览、阶段计数、重复执行、租约续期、旧版本、删除文档、有效激活版本及管理员权限；不调用真实模型。
