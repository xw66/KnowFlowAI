# 容器恢复演练

使用独立 Compose 项目和本地协议替身执行，不调用真实 Embedding、聊天或重排服务，也不会使用正常项目的数据卷：

```powershell
.\scripts\recovery-drill.ps1 -ProjectName knowflow-recovery-20260904
```

脚本启动完整 API、Worker、MySQL、Redis、Kafka、Qdrant、前端和模型替身，然后依次暂停并恢复 Worker、Kafka、Redis、Qdrant。每个阶段都会通过 API 检查任务状态或检索结果，结束后自动停止隔离项目并保留结果文件。

2026-09-04 演练结果为 `PASS`，记录在 `target/recovery-drill-result.json`：Worker 和 Kafka 暂停后的任务均在恢复后完成；Redis 暂停时任务详情回源 MySQL 返回 200；Qdrant 暂停时检索返回 503，恢复后重试返回 5 条结果。该记录验证单次容器级恢复路径，不替代完整生产故障演练或性能结论。
