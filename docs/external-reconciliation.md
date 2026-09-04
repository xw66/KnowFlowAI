# 外部索引只读对账

管理员请求 `GET /api/admin/reconcile/documents?knowledgeBaseId=12&limit=5&afterId=0`。知识库 ID 必填；使用响应的 `nextAfterId` 继续翻页，值为 null 时该知识库文档列表已到末尾。每页最多 10 篇，不返回正文、文件路径或存储密钥，响应禁止缓存。普通用户返回 403。

原有 `GET /api/admin/reconcile` 继续报告 READY 文档缺少激活版本等数据库异常。本接口补充外部存储核验；`database=OBSERVED` 不是数据库全部约束通过，应结合根接口计数和返回的文档状态、激活版本判断。

数据库以文档当前激活版本为依据。文件检查读取有限大小的原文件并比对 SHA-256；Qdrant 读取数据库所记集合中的文档点，比对稳定 point ID、分块 ID、知识库和版本；Lucene 打开已提交 Reader，比对实例、进度版本和分块 ID。Qdrant 使用官方 [Scroll points 接口](https://api.qdrant.tech/api-reference/points/scroll-points)，不请求向量或文档正文。

| 状态 | 含义与处理 |
|---|---|
| OK | 本次检查范围内一致，不代表模型语义或全文内容质量已验证 |
| MISSING / MISSING_CHUNKS | 读取成功但预期文件或分块缺失；确认报告仍有效后，按文档调用现有重新索引接口 |
| MISMATCH / INSTANCE_MISMATCH | 摘要、分块身份、实例不匹配；先确认卷和实例配置，禁止盲删 |
| MISSING_COLLECTION_METADATA | 激活文档缺少向量集合元数据 |
| UNAVAILABLE | 网络、访问权限、目录访问或响应异常，不能解释为数据丢失；恢复服务后重查 |
| NOT_CONFIGURED | 当前进程未配置该索引检查组件 |
| PENDING / NOT_REQUIRED | 尚未完成或当前没有需要核验的激活索引 |
| PENDING_CLEANUP | 已删除文档仍有外部记录，交由已有清理任务处理 |
| RETAINED | 已删除文档的原文件仍保留，不因本报告清理 |
| PARTIAL | 超过每篇 2000 分块检查上限，不给出完整结论 |

`consistency=CHANGED` 表示外部检查期间数据库状态、版本或进度发生改变，必须重新检查；`OBSERVED` 仅代表两次数据库快照相同，不是跨存储原子快照。各存储仍可能在读取之后变化，因此该报告不构成修复授权凭证。

分块缺失明细最多返回 20 个 ID；`expectedChunks`、`observedChunks` 给出检查计数。Qdrant 非激活版本数量记录在 `retainedOtherVersionPoints`，仅标记保留，不将旧版本自动当作可删除数据。向量值、Lucene 分词内容本身不做逐字节比较。

本增量不增加修复接口，不切换激活版本。正常重新索引仍使用现有 `POST /api/knowledge-bases/{id}/documents/{documentId}/reindex`，执行者需具备该知识库 OWNER/EDITOR 权限，并提供幂等键。服务不可用先恢复连接，不能用重建代替诊断。

后续仍需：超上限文档的分块游标检查、数据库未登记的孤立文件/其他集合扫描、限定文档恢复操作记录，以及 Worker 中断和依赖故障恢复演练。本页不能作为“完整对账恢复已完成”的依据。

文件目录扫描：`GET /api/admin/reconcile/documents/files?limit=50&afterKey=`。只扫描配置目录的直属项，按文件名游标分页；`REGISTERED` 会返回对应文档 ID，`ORPHAN` 是数据库没有登记的普通文件，`TEMPORARY` 是未完成上传的临时文件。目录读取失败返回 503 和 `storageStatus=UNAVAILABLE`，不会把故障当成孤立文件。符号链接、目录和特殊文件单独标记，后续人工核验。

## 自动验证

专项命令：`./mvnw.cmd '-Dtest=ReconcileTests,VectorTests#externalReconciliation*' test`。覆盖真实 MySQL 权限及分页、真实 Qdrant 身份不一致与暂停恢复、Lucene 提交缺失、文件摘要变化、删除保留、并发版本变化、检查上限和文件目录分页分类；异常响应结构使用本地替身验证。

完整回归使用 `./mvnw.cmd verify`。Surefire 通过 `KNOWFLOW_CONFIG_IMPORT` 将导入目标设为受版本控制的 `isolated-test.properties`，避免本地 `.env` 的备用模型开关等配置污染普通测试。将 `spring.config.import` 系统属性置空不足以阻止配置文件自身的导入声明，因此直接参数化导入位置。此前的完整回归曾因此多调用一次本地替身，失败断言保留；不放宽调用次数要求。IDE 中应通过该 Maven 命令运行相同验收配置。显式 `LiveFallbackIT` 自行读取 `.env`，仍属独立付费联调，不会进入普通回归。

2026-09-04：最终 JDK 25 `verify` 通过 258 项测试（0 失败、0 跳过），日志 `target/orphan-file-regression.log`。API/Worker 镜像重建和保留数据重启后，生产 Nginx 入口的 3 篇合成文档分为 2 页，文件、Qdrant 和 Lucene 均为 OK，直属目录扫描返回 11 项，Swagger 路径存在；模型调用账本仍为 44 条，没有新增模型调用。临时验收账号已撤销管理员权限并禁用。脱敏响应见 [生产验收记录](validation/2026-09-04-external-reconciliation.json)。
