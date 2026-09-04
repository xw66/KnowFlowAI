# Redis 缓存

## 知识库名称

使用 Spring Boot 管理的 `spring-boot-starter-data-redis`、Lettuce 和 `StringRedisTemplate`，不新增缓存框架。Compose 提供 Redis 8.2.9，绑定本机 6379、AOF 持久化及 PING 健康检查。

启动 `docker compose up -d --wait redis`，设置 `CACHE_ENABLED=true` 后重启应用。缓存默认关闭；远程实例通过 REDIS_HOST、REDIS_PORT、REDIS_PASSWORD 配置。连接与命令超时均为 500ms。接口限流默认启用并要求 Redis，因此 Redis 健康检查默认启用。

当前开发机器的 `.env` 已启用缓存；Redis 容器与打包应用的 Redis 健康组件均验证为 UP。API 与 Worker 重启后读取该配置。

`GET /api/knowledge-bases/{id}` 的响应不变。查询步骤：

1. 从 MySQL 读取当前成员角色、用户和知识库状态、所有者及 cache_version，无权限仍返回 404。
2. 按 `knowflow:kb:name:v1:{id}:{cache_version}` 查询名称；缓存不保存角色或其他权限结论。
3. 未命中时读取相同版本的数据库名称，写入 Redis 并设置 5 分钟 TTL。期间发生改名则直接执行原数据库查询，不把新数据写入旧版本。

V13 增加 knowledge_base.cache_version，初始为 1。改名在同一事务中递增版本，提交后删除旧缓存。删除失败或旧请求迟到回填最多留下一个旧版本键，新请求不会使用它，TTL 最终清理。回滚不会执行失效；事务内读取完全绕过 Redis，防止发布未提交数据。

列表及写入路径保持数据库查询。缓存读写、提交后失效发生 Redis 异常时只记录异常类型，数据库成功不会被改成接口失败；慢 Redis 最多分别等待一次读和一次写的命令超时。仅缓存故障不会绕过数据库权限检查。

权限查询仍访问 MySQL，未证明缓存能降低总体耗时；后续压测会比较启用与关闭缓存的开销。

## 任务状态

`GET /api/document-tasks/{taskId}` 保持原响应结构。V14 为 document_task 增加 cache_version，初始为 1；版本不出现在接口响应中。

- 每次从 MySQL 校验成员、用户状态、知识库状态和文档未删除，再读取当前任务状态与版本。
- PENDING、PROCESSING、RETRY_WAIT 使用 `knowflow:task:v1:{taskId}:{cache_version}` 缓存完整任务视图，TTL 为 5 秒。首次等待 Kafka 接收的 stage 可以为 null。
- SUCCEEDED、FAILED 始终查询数据库。进行中的任务在缓存未命中期间发生变更时，直接回源，不将新状态写入旧版本键。
- 缓存包含任务信息，不包含权限结论；格式无效、读取失败或写入失败均回源或保留已取得的数据库结果。事务内绕过缓存。

Kafka 首次接收、解析认领/完成/失败/耗尽、向量认领/分批完成/失败/耗尽、文档删除撤销任务，都在更新状态的同一条 SQL 中递增版本，并在提交后删除上一版本键。Kafka 重放不递增版本；失去租约的执行者不能更新状态或缓存版本。重新索引创建新任务，新任务有独立版本序列。

两个缓存复用 RedisCache 的读写超时、异常回源和提交后失效实现。TaskCache 只处理任务 JSON、5 秒 TTL 及任务旧版本失效，不引入缓存框架或额外后台线程。

权限查询是请求的授权检查点；与状态提交重叠的读取可以观察到提交前状态，提交后的新请求必须使用新版本。运维修复若修改任务数据，也必须递增 cache_version；数据库直接维护属于后续对账命令的同一约束。

## 验证

KnowledgeBaseTests 使用隔离的真实 MySQL 和 Redis 容器，覆盖 TTL、过期回填、角色变更、撤权、停用用户、事务内读取、回滚、提交后失效、迟到旧版本回填、ACL 拒绝读写/删除，以及暂停 Redis 命令后的超时回源。模型未参与这些测试。

DocumentTests 补充任务缓存 TTL、空 stage、非法 JSON、过期回填、撤权/删除、终态回源、回滚、迟到回填及 Redis 故障。MessagingTests 验证真实 Kafka 接收、重放、解析重试与租约耗尽的缓存变化；VectorTests 验证真实 Qdrant 分批入库、模型协议替身失败与向量租约耗尽。所有测试显式控制缓存开关，启用缓存的测试使用隔离 Redis，避免连接开发实例。

依赖依据：[Spring Boot Redis 配置](https://docs.spring.io/spring-boot/reference/data/nosql.html)、[Redis 官方镜像](https://hub.docker.com/_/redis)。
