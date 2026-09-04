# 评测集 v1 运行记录

2026-09-04 使用独立 Compose 项目 `knowflow-evaluation-v1` 和本地协议替身执行 `scripts/evaluate-dataset.ps1`。脚本上传 20 篇文档，等待 20 个异步任务完成，然后执行 50 条问题的 Vector、BM25、Hybrid 检索，并将逐条响应与汇总保存到 `target/retrieval-evaluation-v1.json`。运行结束后已停止临时项目，正常 `knowflow-ai` 环境未被修改。

协议替身运行汇总如下：

| 模式 | Recall@5 | MRR@5 | nDCG@5 |
|---|---:|---:|---:|
| Vector | 0.16 | 0.0853 | 0.1032 |
| BM25 | 1.00 | 0.9800 | 0.9852 |
| Hybrid | 0.76 | 0.5680 | 0.6164 |

这些数字只证明批量上传、异步入库、指标计算和原始结果保存可运行。协议替身返回固定向量，不具备语义能力，因此不能作为检索质量结论；真实 Embedding 和 `HYBRID + RERANK` 仍需单独运行并记录 `X-Rerank-Status: APPLIED`。
