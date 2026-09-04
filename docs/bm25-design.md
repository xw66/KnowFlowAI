# BM25 增量实施设计

本文件记录 BM25 增量的实现约束；当前验证状态以 roadmap.md 为准。

沿用一个制品、API 与 Worker 两个进程。新增 Lucene core 和 analysis-common，采用 CJKAnalyzer 与 BM25Similarity；不接入新的搜索服务，不改动现有 Embedding 模型空间。

## 文件与数据

- `retrieval/LuceneIndex`：共享目录中的索引读写；仅 Worker 持有 IndexWriter，API 只读已提交数据。
- `ingestion/Bm25TaskProcessor`：从 MySQL 的已激活文档分块建立索引，提交成功后再记录索引进度。
- `bm25_index_progress`：V10 迁移，文档、索引版本、索引实例标识、提交时间、错误与重试状态。
- `app.bm25.*`：启用开关及共享目录。默认关闭，启用时 API 与 Worker 使用同一持久化目录。

Lucene 提交元数据保存索引实例标识。空目录创建新实例，旧 MySQL 进度不能使新实例误判为已就绪；Worker 根据 MySQL 分块重建。文件锁限制同一目录只能有一个 Writer，不能以删除锁文件绕过限制。

## 写入与检索

1. 读取 READY 文档当前激活版本的分块；以稳定分块 ID 幂等更新。
2. Lucene commit 完成后，再向 MySQL 记录该实例、文档和版本的进度；未知提交结果通过重放恢复。
3. 搜索入口增加 BM25 模式，查询前检查知识库权限，不调用 Embedding。
4. Lucene 查询本身包含 knowledge_base_id 过滤，仅返回分块定位与分数。
5. 返回正文前复用当前用户、成员权限、文档 READY、激活版本校验，同时匹配 Reader 实例与已提交进度。
6. 删除先在 MySQL 隐藏，Worker 再清除 Lucene 文档；在途写入残留由持续同步复查处理。

BM25 进度独立于向量任务；向量激活后到 BM25 提交前存在短暂索引延迟，不能把未提交的 BM25 版本用于检索。历史分块用于重建，不能无条件清空 MySQL 或共享目录。

## 验收

使用真实 MySQL 和临时磁盘索引验证中文关键词、稳定 ID 重放、仅提交后可读、版本切换、撤权、跨库访问、删除、Worker 重启和空索引恢复。完整回归通过后才开始 Hybrid / RRF，不将 BM25 相关性分数直接与向量分数相加。

参考：[Lucene Reader 与提交视图](https://lucene.apache.org/core/10_3_0/core/org/apache/lucene/index/package-summary.html)、[IndexSearcher](https://lucene.apache.org/core/10_3_2/core/org/apache/lucene/search/IndexSearcher.html)。
