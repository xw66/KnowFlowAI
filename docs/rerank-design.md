# Rerank 增量设计

沿用搜索接口，增加可选 `rerank` 布尔参数，仅 HYBRID 可开启。默认请求与服务开关均关闭；不增加数据库表和 SDK。使用 JDK HttpClient 调用百炼原生文本排序协议，模型、完整 URL、密钥、超时与候选上限可配置。

RRF 融合并授权后截取前 20 个候选（可调 20–50）；以候选正文和原问题请求完整排列，不让模型返回内容替换 MySQL 原文。返回数量、位置索引唯一性及范围、分数有限性与 0–1 范围均须验证。按分数降序、同分按原 RRF 顺序排列。

重排在数据库事务外调用，总等待默认 5 秒，超时取消请求，不自动重试。JDK 25 BodyHandlers.limiting 在接收阶段限制响应为 128 KiB。异常退回原 RRF 顺序。调用完成或失败后，仍按原查询的向量集合及 Lucene 实例重新授权，剔除已撤权、删除、非激活版本和已失效候选。

响应数组保持兼容，通过 `X-Rerank-Status` 区分 NOT_REQUESTED / DISABLED / INSUFFICIENT_CANDIDATES / APPLIED / FALLBACK，`X-Search-Score-Type` 标明 VECTOR / BM25 / RRF / RERANK。只有 APPLIED 才返回重排模型名；评测不能把其他状态计为 Hybrid + Rerank。

验证使用本地 HTTP 替身和真实数据库、Qdrant、Lucene，覆盖正常重排、失败和超时、非法响应、原文引用保持、调用中撤权及版本失效。真实服务只做两条短句的一次连接验证，记录实际返回 usage，不声称质量提升。

参考：[百炼文本排序协议](https://help.aliyun.com/zh/model-studio/text-rerank-api)。
