# KnowFlow AI 实施设计

企业知识治理与智能检索平台。本文为目标设计，不代表功能已经实现；实际进度以 README 为准。

## 运行结构

保留现有 Maven 单模块和包名 `io.github.xw66.knowflowai`。同一制品以 `api`、`worker` Profile 启动两个独立进程，业务代码按功能组织。处理器落地时再创建 Profile，不创建空模块。

```text
KnowFlow AI/
├── pom.xml
├── src/main/java/io/github/xw66/knowflowai/
│   ├── KnowFlowAiApplication.java
│   ├── auth/
│   ├── knowledge/
│   ├── document/
│   ├── ingestion/
│   ├── retrieval/
│   ├── chat/
│   └── model/
├── src/main/resources/
│   ├── application.properties
│   ├── application-api.properties
│   ├── application-worker.properties
│   └── db/migration/
├── src/test/
├── docs/
├── evaluation/
├── Dockerfile
└── compose.yaml
```

API 负责鉴权、业务事务、上传、检索和 SSE；Worker 负责文档处理与索引写入。MySQL 是业务状态及权限的权威来源。Redis 不作为唯一任务状态或唯一幂等依据。Kafka 使用至少一次投递，依靠业务幂等实现可恢复处理。

最终 Compose 包含 API、Worker、MySQL 8.4、Redis 8、Kafka 4.3.x、Qdrant，固定实际验证过的镜像版本，不使用 latest。文件卷 API 可写、Worker 只读；Lucene 索引卷 Worker 可写、API 只读。首次交付只启动健康检查服务。

## 依赖策略

- JDK 25，Spring Boot 4.1.1，Maven Wrapper；Spring Security 版本跟随 Boot BOM。
- Spring AI 计划使用 2.0.1，接入模型时验证该精确版本及所选 provider、Qdrant 集成；本阶段不预装 AI 依赖。
- Spring MVC、Bean Validation、Security Resource Server、JDBC、Kafka、Data Redis、Actuator 优先使用 Spring 原生支持。JWT 使用 Spring Security 的编解码能力，不另写密码学逻辑。
- MySQL 迁移使用 Flyway；接入时验证 Boot 4 starter 与 MySQL 模块。
- PDF 使用 PDFBox 保留页码，DOCX 使用 Apache POI 保留段落位置；TXT / Markdown 使用 JDK UTF-8 读取。具体版本在对应增量锁定。扫描 PDF 无文本时明确失败，不伪造 OCR 结果。
- BM25 使用 Apache Lucene；中文分词先用 CJKAnalyzer，评测后再决定是否替换。Qdrant 存储稠密向量。不将 MySQL FULLTEXT 的相关性分数标为 BM25。
- 前端先提供接口示例，业务接口落地时接入与 Boot 4 兼容的 Swagger/OpenAPI；Vue 3 后置。
- 只有实际依赖冲突无法低成本解决时才评估 Boot 3.5.x + Spring AI 1.1.x + Security 6 的整套降级，不混搭大版本。

参考：[Spring AI 版本兼容](https://docs.spring.io/spring-ai/reference/getting-started.html)、[Boot 4.1.1 发布](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/)、[Boot 4 starter 迁移](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)、[Lucene BM25](https://svn.apache.org/repos/infra/sites/lucene/core/9_12_2/core/org/apache/lucene/search/similarities/BM25Similarity.html)。

## 数据库表设计

以下是逻辑表设计；每个功能落地时才新增对应 Flyway SQL，不提前建未使用的表。

约定：MySQL InnoDB、utf8mb4，业务 ID 为 BIGINT 自增，时间为 UTC DATETIME(6)，所有表具有 created_at，可变实体具有 updated_at。引用关系使用外键；除明确可空字段外均 NOT NULL。状态用 VARCHAR 配合 CHECK 约束与应用枚举，JSON 只承载快照或事件载荷。所有 SQL 采用参数绑定。

| 表 | 主要字段 | 唯一约束与访问索引 |
|---|---|---|
| app_user | id，username VARCHAR(64)，password_hash VARCHAR(255)，system_role VARCHAR(16)，status VARCHAR(16) | UNIQUE(username)；用户名规范化后存储；角色 USER / ADMIN；状态 ACTIVE / DISABLED |
| knowledge_base | id，name VARCHAR(128)，owner_id FK app_user，status VARCHAR(16) | INDEX(owner_id, status)；ACTIVE / DELETED |
| knowledge_member | id，knowledge_base_id FK，user_id FK，role VARCHAR(16) | UNIQUE(knowledge_base_id, user_id)，INDEX(user_id, knowledge_base_id)；OWNER / EDITOR / VIEWER |
| document | id，knowledge_base_id FK，uploaded_by FK app_user，name VARCHAR(255)，storage_key VARCHAR(255)，sha256 CHAR(64)，media_type VARCHAR(128)，size_bytes BIGINT，status VARCHAR(24)，index_version INT，active_index_version INT NULL | UNIQUE(storage_key)，INDEX(knowledge_base_id, status)，INDEX(knowledge_base_id, sha256)；哈希不在不同知识库间自动去重 |
| document_task | id，document_id FK，index_version INT，status VARCHAR(24)，attempts INT，max_attempts INT，next_attempt_at DATETIME(6) NULL，lease_token CHAR(36) NULL，lease_until DATETIME(6) NULL，error_code VARCHAR(64) NULL，error_message VARCHAR(1000) NULL，started_at / finished_at DATETIME(6) NULL | UNIQUE(document_id, index_version)，INDEX(status, next_attempt_at)，INDEX(status, lease_until) |
| outbox_event | id，task_id FK，event_type VARCHAR(64)，payload JSON，status VARCHAR(16)，attempts INT，available_at DATETIME(6)，lease_until DATETIME(6) NULL，published_at DATETIME(6) NULL | INDEX(status, available_at)；PENDING / PUBLISHING / PUBLISHED |
| document_chunk | id，document_id FK，index_version INT，chunk_no INT，content MEDIUMTEXT，page_start / page_end INT NULL，paragraph_start / paragraph_end INT NULL，token_count INT，vector_point_id CHAR(36)，embedding_model VARCHAR(128) | UNIQUE(document_id, index_version, chunk_no)，UNIQUE(vector_point_id)；页码或段落至少有一组有效位置 |
| conversation | id，user_id FK，title VARCHAR(200) | INDEX(user_id, created_at)；本人可读写 |
| chat_message | id，conversation_id FK，role VARCHAR(16)，content MEDIUMTEXT，status VARCHAR(24)，request_id CHAR(36) | UNIQUE(conversation_id, request_id, role)，INDEX(conversation_id, id)；PENDING / STREAMING / COMPLETED / FAILED / CANCELLED |
| message_citation | id，message_id FK，citation_no INT，document_id FK，chunk_id FK，document_name VARCHAR(255)，excerpt TEXT，page_start / page_end INT NULL，paragraph_start / paragraph_end INT NULL | UNIQUE(message_id, citation_no)；保存回答时引用快照，历史访问仍需检查当前权限 |
| model_call | id，user_id FK NULL，task_id FK NULL，message_id FK NULL，request_id CHAR(36)，operation VARCHAR(24)，provider / model VARCHAR(128)，attempt_no INT，status VARCHAR(24)，input_tokens / output_tokens BIGINT NULL，duration_ms BIGINT，estimated_cost DECIMAL(20,8) NULL，currency CHAR(3) NULL，pricing_version VARCHAR(64) NULL，usage_source VARCHAR(16)，error_code VARCHAR(64) NULL | INDEX(request_id, operation, attempt_no)，INDEX(created_at, provider, model)；CHAT / EMBEDDING / REWRITE / RERANK；含失败与 Fallback 尝试 |

平台角色决定系统级操作，知识库角色决定普通用户的内容访问；ADMIN 可访问全部有效知识库并管理开放范围与成员。注册不能指定 ADMIN。创建知识库与 OWNER 成员写入同一事务，避免无所有者知识库；所有者变更也必须事务更新。

初版文档继承知识库 ACL，所有文档均有明确权限边界。暂不提供文档级例外授权 UI；若需要同库不同权限，再引入文档 ACL。删除采用软删除，先撤销可检索状态，再异步清理两种索引与文件；引用历史仅向仍有权限的用户展示。

## 文档处理与一致性

1. API 校验成员 EDITOR / OWNER 权限、扩展名、真实文件类型、大小和空文件；生成存储键，禁止直接以原文件名构建路径。文件写到临时位置后原子移动，失败清理；数据库失败留下的孤立文件由定期清理任务回收。
2. 同一 MySQL 事务写 document、document_task、outbox_event，返回 202 和任务 ID。请求线程不解析、不调用 Embedding。客户端重试使用有作用域的 Idempotency-Key，校验请求摘要，不允许同键不同文件复用结果。
3. Outbox 发布器等待 Kafka broker 确认后标记已发送。发送成功但更新失败可以重复投递；事件只包含任务 ID / 版本，Worker 从 MySQL 读取真实文件元数据。
4. Worker 原子抢占数据库任务租约并增加 attempts；Redis SET NX + TTL 用于快速排重。Redis 重启不影响数据库唯一约束与租约保证。已完成或租约有效的重复消息不重复处理。
5. 解析保留出处，再清洗、分块，稳定生成向量点 ID；每阶段通过 lease_token 条件更新，续租失败立即停止提交。过期执行者使用隔离的索引写入代次，只有持有当前租约的结果可发布，防止旧 Worker 覆盖新结果。
6. 写入 Chunk、Qdrant、Lucene 后，最后事务激活 active_index_version 并置 SUCCEEDED。检索只接收数据库确认激活且 READY 的版本。外部索引不可与 MySQL 原子提交，依靠稳定 ID upsert、可重建索引及后台对账恢复。
7. 可重试失败记录 RETRY_WAIT、next_attempt_at，按有上限的指数退避重试；调度器通过 Outbox 再投递。超上限或永久失败进入 FAILED。捕获业务失败并持久化调度结果后才提交消费位点；数据库不可用则不确认消息。损坏事件进入死信并告警。
8. 租约回收器恢复崩溃任务。手动重新处理创建新 index_version，保留旧任务历史；旧版本未清理前仍受版本与权限过滤。

任务状态：PENDING → PROCESSING → SUCCEEDED；PROCESSING → RETRY_WAIT → PROCESSING；PROCESSING / RETRY_WAIT → FAILED。stage 单独记录 PARSE / CHUNK / EMBED / INDEX，避免将业务阶段和重试状态混成一个巨大状态枚举；实现时增加 stage 字段。

Lucene 初版为单 Worker 写入、API 读取同主机共享卷，通过已提交索引定期刷新 Reader；MySQL Chunk 可以重建索引。该方案不支持跨主机共享普通本地文件系统；需要横向扩容时迁移到 OpenSearch 等现有搜索引擎，不自研分布式索引。

## 问答、权限与模型

- JWT 验签并校验 issuer、audience、到期时间；服务端查询账号状态。密码采用单向自适应哈希；密钥只从环境或密钥文件注入。匿名注册、登录独立限流，业务接口默认需要鉴权。
- 请求限定目标知识库；从 MySQL 解析允许集合。查询改写可独立失败并退回原问题。BM25 与向量检索都先按允许知识库过滤，禁止全库检索后才过滤 TopK。
- 对结果以稳定 Chunk ID 去重，RRF 分数为各榜单 1 / (k + rank) 之和。Rerank 是明确的扩展点，接入时实现真实重排并保留关闭开关，不能将原顺序当成 Rerank 评测结果。
- 生成前再次验证账号、成员关系、文档状态与激活版本；缺少证据时直接返回无法从知识库确认。文档内容视为不可信数据，不能覆盖系统指令。
- 引用采用服务端分配的编号，模型只能引用候选编号；校验后返回文档名、原文、页码或段落号。无依据的引用不发送，不能根据模型输出随意拼文件地址。
- SSE 事件定义为 metadata、delta、citation、usage、done、error；响应提交后错误使用 error 事件。记录中断、取消与部分输出，不把半截回答标为成功；客户端断开取消上游调用。
- HTTP/SSE 流程使用 Spring MVC；模型响应流由 Spring AI 提供，限制缓冲和并发，禁止为每个 token 单独持久化消息。
- 模型设置连接、首 token、空闲和总调用期限；仅瞬态错误有限重试，避免叠加多层重试。仅在未向用户输出正文前切换备选模型；已输出后失败保留部分内容并发送错误，不拼接两个答案。
- 聊天 Fallback 与 Embedding 分开：向量检索和入库必须使用同一模型与维度；不能临时切换 Embedding 导致向量空间混用，替换需要全量版本化重建。
- 每次真实调用独立计数，记录实际模型、操作、重试和耗时。模型未返回 usage 时记 NULL / UNKNOWN，估算值明确标为 ESTIMATED。价格表含版本与币种，成本为估算，不冒充账单。

## 缓存、错误与可观测性

- 任务与热点知识库元数据采用 cache-aside 和有限 TTL，事务提交后删除缓存；任务关键终态查询读 MySQL，缓存返回带 updated_at，允许明确的短暂状态延迟。
- 权限决定始终读 MySQL，不依赖可能尚未失效的成员缓存；内容缓存返回前重新授权。先保证撤权安全，再通过真实压测决定是否优化权限查询。
- Redis 限流使用原子 Lua 固定窗口，Key 包含用户 / IP 与接口。幂等记录包含请求摘要与执行结果，释放锁时校验令牌，禁止删掉其他请求的锁。
- Redis 不可用时热点读取退回 MySQL；涉及安全的入口限流采用明确的拒绝策略，不能静默无限放行。任务执行仍由数据库约束兜底。
- 参数校验使用 Bean Validation；统一异常使用 ProblemDetail，区分 400、401、403/404、409、413、415、429、503；不向客户端暴露堆栈、SQL、密钥或文档正文。
- 结构化日志记录 requestId / taskId / documentId、阶段、耗时和错误码，不记录 JWT、密码、完整问题和检索正文。监控与业务统计分开，Actuator 的敏感端点不公开。

## 小步实施与测试门槛

每行可拆成多轮；每轮只实现一个能通过测试演示的功能，完成后更新 README。不要把规划中的功能描述为已完成。

| 增量 | 可运行交付 | 该增量必须执行的测试 |
|---|---|---|
| 01 | HTTP 启动、Actuator 健康检查 | 随机端口真实 HTTP；健康结果；敏感管理端点未暴露 |
| 02 | MySQL / Flyway 与注册 | 真实 MySQL 迁移、有效注册、重复用户名、密码不明文、非法参数 |
| 03 | 登录、JWT、系统 RBAC | 正确/错误密码、过期/伪造令牌、越权、禁用用户 |
| 04 | 知识库创建与成员授权 | 所有者原子创建、非成员拒绝、读写分级、撤权立即生效 |
| 05 | 上传与事务 Outbox | 四种文件、大小/类型拒绝、返回 202、请求不调用解析、重复请求、Kafka 暂停 |
| 06 | Kafka Worker 与状态机 | 重复消息、消费者崩溃、租约过期、旧执行者、退避重试及失败终态 |
| 07 | 解析和分块 | PDF 页码、多段 DOCX、中文、空白文件、损坏文件、边界和原文可追溯 |
| 08 | Embedding / Qdrant / 索引发布 | 重放不重复、部分写失败恢复、向量维度校验、未激活版本不返回 |
| 09 | BM25 与向量检索 | 关键词、语义问题、中文、跨库拒绝、删除/撤权过滤 |
| 10 | RRF 与真实 Rerank | 固定榜单融合、去重、重排禁用/启用、模型故障 |
| 11 | Spring AI / SSE / 引用与会话 | 事件顺序、首段/末段、断开取消、引用编号与授权、无证据拒答 |
| 12 | 模型容错与统计 | 注入超时/429/5xx、首段前 Fallback、首段后错误、Token 缺失、限流并发 |
| 13 | Redis 缓存与一致性 | 提交后失效、并发读写、Redis 停机、幂等键冲突、释放锁令牌验证 |
| 14 | 评测、压测与完整 Compose | 空卷启动、重启恢复、端到端上传到问答、真实外部模型冒烟及可复现报告 |

涉及 MySQL、Redis、Kafka、Qdrant 的行为使用真实容器集成测试，不用 H2 或 Mock 结果替代兼容性证明；模型可用故障注入服务测容错，但报告须注明，真实模型检索/生成验证另行记录。

## 评测和简历证据

检索数据集采用 JSONL，包含 query、知识库范围、相关 chunk/document 标签；分为调参集与独立测试集。固定语料版本、分块参数、Embedding 模型、TopK、RRF 参数、Rerank 模型与随机种子。三个实验仅改变检索策略：Vector、Hybrid、Hybrid + Rerank；未配置重排模型时第三组标记未执行。

保存原始逐查询结果，统计 Recall@K、MRR、nDCG@K、检索 p50/p95。回答质量另外评估引用准确率、证据覆盖率与拒答表现，标明人工/模型判分及模型版本。越权测试单独统计，不能混入一般召回率掩盖泄露。

压测记录 CPU、内存、数据量、索引量、并发、持续时间、预热、缓存命中率、错误率、吞吐、首 token 延迟和完整响应延迟。区分本地 stub 压测与真实模型测试。原始数据和运行命令入库后才给出结论；没有测量就不填写 QPS、延迟、召回率提升或成本下降数字。
