# 评测集 v1 运行记录

2026-09-04 使用独立 Compose 项目 `knowflow-evaluation-v1` 和本地协议替身执行 `scripts/evaluate-dataset.ps1`。脚本上传 20 篇文档，等待 20 个异步任务完成，然后执行 50 条问题的 Vector、BM25、Hybrid 检索，并将逐条响应与汇总保存到 `target/retrieval-evaluation-v1.json`。运行结束后已停止临时项目，正常 `knowflow-ai` 环境未被修改。

协议替身运行汇总如下：

| 模式 | Recall@5 | MRR@5 | nDCG@5 |
|---|---:|---:|---:|
| Vector | 0.16 | 0.0853 | 0.1032 |
| BM25 | 1.00 | 0.9800 | 0.9852 |
| Hybrid | 0.76 | 0.5680 | 0.6164 |

这些数字只证明批量上传、异步入库、指标计算和原始结果保存可运行。协议替身返回固定向量，不具备语义能力，因此不能作为检索质量结论；真实 Embedding 和 `HYBRID + RERANK` 仍需单独运行并记录 `X-Rerank-Status: APPLIED`。

## 真实服务结果

随后在本地已配置的百炼服务上使用同一数据集运行，未输出密钥。真实 Embedding 评测和带 Rerank 的评测原始结果分别保存为 `docs/validation/2026-09-04-live-retrieval-evaluation-v1.json` 与 `docs/validation/2026-09-04-live-retrieval-evaluation-v1-rerank.json`：

| 模式 | Recall@5 | MRR@5 | nDCG@5 | Rerank APPLIED |
|---|---:|---:|---:|---:|
| Vector | 1.00 | 0.9900 | 0.9926 | 0/50 |
| BM25 | 1.00 | 0.9900 | 0.9926 | 0/50 |
| Hybrid | 1.00 | 1.0000 | 1.0000 | 0/50 |
| Hybrid + Rerank | 1.00 | 1.0000 | 1.0000 | 50/50 |

本次真实运行记录 290 次 `text-embedding-v4` 调用和 50 次 `gte-rerank-v2` 调用，账本估算成本合计 0.0347337 元。数据集规模有限且问题为人工构造，结果只适用于该版本样本；Rerank 未提升指标，不能据此宣称普遍收益。
