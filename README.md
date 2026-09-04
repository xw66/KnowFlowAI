# KnowFlow AI

企业知识治理与智能检索平台，面向 Java 后端与 AI 应用开发实践。目录、表设计、业务流程和验收计划见 [实施设计](docs/design.md)。

已实现：HTTP 健康检查、MySQL / Flyway 迁移、用户注册与登录、BCrypt 密码哈希、JWT 鉴权、系统 RBAC、知识库与成员权限、文件上传、文档任务与事务 Outbox、Kafka 可靠投递与 Worker 幂等接收、参数校验、唯一约束、ProblemDetail 错误响应及 JSON 结构化日志。

已实现权限向量检索、BM25、Hybrid / RRF、可选 Rerank、同步与 SSE 证据问答、真实模型连通性验证、Swagger、文档管理及异步删除清理。已接入会话持久化、可选查询改写、模型超时及可选 Fallback、可选的 [知识库与任务状态缓存](docs/cache-design.md)，以及默认启用的 [原子接口限流](docs/rate-limit-design.md) 和 [Redis 请求幂等协调](docs/idempotency-design.md)。调用统计与完整应用 Compose 尚待实施；完整进度见 [实施进度](docs/roadmap.md)。当前 Compose 包含 MySQL、Redis、Kafka 与 Qdrant。Worker 接收后任务为 PENDING / QUEUED；四种文件提取正文并分块后为 PENDING / CHUNKED；启用 Embedding 后继续向量入库。

## 环境与启动

- JDK 25，Spring Boot 4.1.1，Maven Wrapper 3.9.16。
- Docker Desktop 使用 Linux containers，须能成功执行 `docker info`。
- MySQL 8.4.8，Compose 与集成测试使用相同镜像版本。
- Apache Kafka 4.3.1，Spring Kafka 版本由 Spring Boot BOM 管理。

首次运行，在项目根目录复制环境文件（不要覆盖已有 `.env`）：

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，为 `DB_PASSWORD`、`MYSQL_ROOT_PASSWORD` 填写不同的随机密码，可用密码管理器生成长字母数字密码。示例文件不含默认密码。`.env` 已被 Git 忽略；Compose 与 Spring Boot 均从项目工作目录读取它，环境变量可覆盖同名配置。

同时填写 `JWT_SECRET`，内容为至少 32 个随机字节的 Base64 编码。可以在本机 PowerShell 运行以下命令生成后填入 `.env`，不要将密钥提交到 Git：

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

当前开发机器的 `.env` 已补齐随机 JWT 密钥，无需重新生成。密钥缺失、Base64 格式错误或长度不足时应用拒绝启动。

```powershell
docker compose up -d --wait mysql redis kafka qdrant
.\mvnw.cmd -version
.\mvnw.cmd spring-boot:run
```

默认数据库端口为 `127.0.0.1:3307`，应用账号与数据库名均为 `knowflow`，HTTP 端口为 8080。Flyway 在启动时自动迁移；数据库不可用或迁移失败会阻止应用启动。修改 `MYSQL_PORT` 同时影响 Compose 端口映射和应用默认 URL。连接外部 MySQL 时设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。

MySQL 初始化密码只对空数据卷生效，修改 `.env` 不会修改已有数据库密码。`docker compose stop mysql` 停止数据库并保留数据；不要为改密码删除数据卷。

IDEA 打开 pom.xml，项目 SDK、Maven Runner JRE 选 JDK 25，工作目录设为项目根目录，运行 `KnowFlowAiApplication`。终端 JAVA_HOME 与 IDEA SDK 独立，本机可在当前 PowerShell 显式选择：

```powershell
$env:JAVA_HOME = 'D:\Environment Variable\scoop\apps\temurin-lts-jdk\25.0.3-9.0.LTS'
.\mvnw.cmd -version
```

## 注册接口

`POST /api/auth/register`，请求头 `Content-Type: application/json`：

```json
{"username":"Alice_01","password":"Example_only_123!"}
```

```powershell
$body = @{ username = 'Alice_01'; password = 'Example_only_123!' } | ConvertTo-Json
Invoke-RestMethod http://localhost:8080/api/auth/register -Method Post -ContentType 'application/json' -Body $body
```

成功返回 HTTP 201，例如 `{"id":1,"username":"alice_01","role":"USER"}`。ID 为数据库生成的实际值，不返回密码、哈希或 JWT。

- 用户名：3–64 位英文字母、数字或下划线；不接受空格；统一小写存储，大小写视为同名。
- 密码：8–72 个 Java 字符单元，同时 UTF-8 编码不超过 72 字节；不修剪、不截断。BCrypt 使用独立随机盐，保存带 `{bcrypt}` 标识的哈希。
- 新用户固定 USER / ACTIVE；未知字段（包括 role、systemRole）返回 400。
- 用户名冲突返回 409，格式错误返回 400，数据库操作失败返回脱敏的 503。并发重复注册由 MySQL 唯一索引保证只有一条记录。

错误采用 `application/problem+json`，参数校验失败附带 `errors`，不回显密码或 SQL。例如：

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "请求参数校验失败",
  "instance": "/api/auth/register",
  "errors": {"username":"用户名须为 3–64 位英文字母、数字或下划线"}
}
```

`GET /actuator/health` 正常返回 `status: UP`，现在包含数据库健康检查。仅公开 health 管理端点，隐藏内部组件与详情。

## 登录与权限

`POST /api/auth/login` 接收与注册相同的 username / password JSON。成功返回 HTTP 200：

```json
{"accessToken":"实际签发的JWT","tokenType":"Bearer","expiresIn":900}
```

```powershell
$login = Invoke-RestMethod http://localhost:8080/api/auth/login -Method Post -ContentType 'application/json' -Body $body
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
Invoke-RestMethod http://localhost:8080/api/auth/me -Headers $headers
```

`GET /api/auth/me` 返回当前用户的 id、username、role、status，不返回密码或哈希。必须通过 `Authorization: Bearer ...` 请求头传令牌；Cookie、查询参数不用于登录，服务端不建立 Session。认证相关响应禁止缓存。

| 接口 | 权限与行为 |
|---|---|
| POST /api/auth/register | 匿名注册，仅创建 USER |
| POST /api/auth/login | 匿名登录；未知用户、密码错误、禁用账号统一返回 401 |
| GET /api/auth/me | 有效令牌且账号 ACTIVE |
| GET /api/admin/users/{id} | 当前角色为 ADMIN；查看指定用户的公开账号信息，目标不存在为 404 |
| GET /actuator/health | 匿名健康检查 |

其他 API 默认要求身份认证；其余路径默认拒绝。安全过滤器的 401 / 403 与 MVC 错误均采用 ProblemDetail，未登录或令牌无效返回 401，已登录但角色不足返回 403。数据库查询故障返回脱敏 503，不能绕过权限检查。

JWT 使用 HS256，校验签名算法、签名、issuer、audience、有效期和用户 ID；必须有 exp，到期立即失效。默认 issuer 为 `knowflow-ai`、audience 为 `knowflow-api`、有效期为 15 分钟，可用 `app.jwt.*` 配置调整。令牌只承载身份，不承载授权决定；每次请求从 MySQL 读取账号状态与角色，因此角色降级、禁用或删除用户会对已有令牌生效。

管理员不通过公开注册创建。需要本地演示时，由可信数据库操作者对已注册账号执行如下 SQL；自动测试只在独立临时数据库中设置角色，不会提升开发库账号：

```sql
UPDATE app_user SET system_role = 'ADMIN' WHERE username = 'alice_01';
```

初版没有 refresh token、服务端单令牌注销或管理角色编辑接口。客户端退出时丢弃令牌；重新启用账号后，之前尚未过期的令牌仍可使用。系统 ADMIN 角色不会自动取得未来知识库的内容权限。

## 知识库与成员权限

所有接口使用 Bearer JWT，用户 ID 从鉴权结果取得，不能通过请求体指定操作者或所有者。V2 迁移创建 `knowledge_base` 与 `knowledge_member`；已有 V1 数据保留，重启应用自动升级。

| 接口 | 说明 |
|---|---|
| POST /api/knowledge-bases | `{"name":"研发知识库"}`；201 返回 id、name、ownerId、role，创建者自动为 OWNER |
| GET /api/knowledge-bases | 仅列出自己参与的 ACTIVE 知识库；按 id 升序 |
| GET /api/knowledge-bases/{id} | 返回有权限访问的知识库详情，role 为当前用户在该库的角色 |
| PUT /api/knowledge-bases/{id} | OWNER / EDITOR 修改名称，请求为 `{"name":"新名称"}` |
| GET /api/knowledge-bases/{id}/members | 仅 OWNER 可列出成员的 userId、username、role、status |
| PUT /api/knowledge-bases/{id}/members/{userId} | OWNER 添加成员或修改角色，`{"role":"EDITOR"}` 或 `{"role":"VIEWER"}`；成功 204 |
| DELETE /api/knowledge-bases/{id}/members/{userId} | OWNER 移除非 OWNER 成员；成功 204，重复移除仍为 204 |

| 操作 | OWNER | EDITOR | VIEWER | 非成员（包括系统 ADMIN） |
|---|---|---|---|---|
| 查看知识库 | 允许 | 允许 | 允许 | 404 |
| 修改知识库名称 | 允许 | 允许 | 403 | 404 |
| 列出 / 授权 / 移除成员 | 允许 | 403 | 403 | 404 |

知识库列表参数为 `afterId`（默认 0）和 `limit`（默认 50，范围 1–100）；成员列表使用 `afterUserId` 和相同 limit。下一页传入上一页最后一个 id / userId；返回空数组表示没有后续数据。分页先按权限过滤，再取指定数量。

名称非空、最长 128 个 Java 字符单元，存储前去除两端空白。同名知识库允许存在，以 id 区分。未知字段、非法角色、非正数 ID 或越界分页参数返回 400。目标用户不存在或被禁用时授权返回 404。

本轮不提供知识库删除、OWNER 转让或自助退出接口。OWNER 不能通过普通成员接口被降级或移除，返回 409；普通授权也不能赋予 OWNER。创建知识库和 OWNER 成员是一个事务，失败时全部回滚。

知识库写操作与成员变更先锁定同一知识库行，再读取权限，在短数据库事务内完成。已经取得锁的写入先于后续撤权完成；撤权提交后的请求会重新查库并被拒绝。权限结果不放入 Redis 或 JWT。DELETED 知识库对所有用户隐藏，非成员与不存在知识库使用相同的 404 提示。

登录后使用上一节的 `$headers`：

```powershell
$kb = Invoke-RestMethod http://localhost:8080/api/knowledge-bases -Method Post -Headers $headers -ContentType 'application/json' -Body '{"name":"研发知识库"}'
Invoke-RestMethod http://localhost:8080/api/knowledge-bases -Headers $headers
Invoke-RestMethod "http://localhost:8080/api/knowledge-bases/$($kb.id)/members" -Headers $headers
```

邀请成员时，将下面的 `$memberUserId` 设为目标用户注册响应中的实际 id：

```powershell
Invoke-RestMethod "http://localhost:8080/api/knowledge-bases/$($kb.id)/members/$memberUserId" -Method Put -Headers $headers -ContentType 'application/json' -Body '{"role":"VIEWER"}'
```

## 文档上传与任务查询

文档目录接口：

| 接口 | 行为 |
|---|---|
| GET /api/knowledge-bases/{id}/documents | 当前成员可读，afterId 默认 0，limit 默认 50、范围 1–100，按文档 ID 升序游标分页 |
| GET /api/knowledge-bases/{id}/documents/{documentId} | 返回文档名称、类型、字节数、状态、indexVersion、activeIndexVersion，以及最新任务 ID、状态、阶段和错误码 |

两者都设置 Cache-Control: no-store，不返回存储键、摘要或正文。非成员、跨库文档、不可用知识库和已删除文档返回 404；系统 ADMIN 不绕过成员权限。最新任务失败仍可在目录查看错误码。

`POST /api/knowledge-bases/{id}/documents/{documentId}/reindex` 重新解析原文件，必须提供 Idempotency-Key，不需要请求体。只有 OWNER / EDITOR 可调用；最新任务必须已 SUCCEEDED 或 FAILED，否则返回 409。同文档、同用户、同幂等键的请求完成后，重复调用返回原任务；请求仍在处理中时返回 409，稍后用原键重试。成功返回 202，响应与上传相同，Location 指向新任务查询地址。

V8 为任务增加请求用户及重建幂等键。版本号递增、新任务和 Outbox 同事务提交，失败时整体回滚。已有激活版本时 document 保持 READY，新任务进度从 latestTaskStatus / latestTaskStage 查看；新任务失败不隐藏旧版本，仅在新版本全部向量写入成功后切换 active_index_version。该保证针对同一服务模型集合；变更模型或地址后，查询仍必须使用与目标激活集合一致的配置。原文件和历史分块暂时保留，不提供原文件替换接口。

`DELETE /api/knowledge-bases/{id}/documents/{documentId}` 限 OWNER / EDITOR，成功及重复删除均返回 204。MySQL 事务将文档置 DELETED、清空激活版本、撤销未完成任务租约并记录 V9 `vector_cleanup`。删除后列表、详情、任务和搜索不再返回文档；重新处理返回 404，复用原上传幂等键返回 409。

Worker 即使关闭 Embedding 也会执行向量清理，不调用模型。清理按数据库记录的所有历史集合及 document_id 限定范围，使用 [Qdrant 按过滤条件删除接口](https://api.qdrant.tech/api-reference/points/delete-points) 并等待确认；失败退避重试，间隔上限 300 秒，崩溃后的 60 秒租约可接管。成功保留墓碑，每小时复查一次，清除删除前在途请求的迟到写入；因此逻辑删除立即生效，物理清理最终收敛。墓碑数量随删除量增长，后续索引对账阶段再优化扫描及保留策略。

原始文件、MySQL 历史分块和任务暂时保留供审计与后续引用对账，本接口不是全部介质的物理擦除。不要直接清空共享存储目录。当前没有恢复已删除文档的接口。

V3 迁移新增 `document`、`document_task`、`outbox_event`。上传请求只完成文件验证、持久化和任务创建，不调用解析器、Embedding 或大模型。

`POST /api/knowledge-bases/{id}/documents` 使用 `multipart/form-data`，文件字段名为 `file`，必须携带 `Idempotency-Key` 请求头。成功返回 HTTP 202，Location 指向任务查询地址：

```json
{"documentId":1,"taskId":1,"status":"PENDING"}
```

默认允许 `.pdf`、`.docx`、`.md`、`.txt`，扩展名不区分大小写；单文件最多 10 MB，整个 multipart 请求最多 11 MB。空文件或纯空白文本返回 400，类型不支持或内容不符返回 415，超过大小限制返回 413。TXT / Markdown 必须是 UTF-8；文件名不允许包含路径或控制字符。

上传阶段只验证 PDF 文件头、DOCX ZIP 必需条目、文本编码和内容类型，不进行完整 PDF / Office 语义校验。DOCX 解压内容限 32 MB、条目限 2000，防止检查过程被压缩炸弹拖垮；文档是否损坏、是否有可提取正文，在后续 Worker 解析阶段判断。

只有 OWNER / EDITOR 可以上传；VIEWER 返回 403，非成员（包括系统 ADMIN）返回 404。上传前先检查权限，文件落盘后进入数据库短事务，再取得知识库锁重新检查权限，防止上传期间撤权后仍提交任务。

幂等键为 8–128 位字母、数字或 `. _ : -`，建议使用 UUID。作用域为「知识库 + 当前用户」：请求完成后，同键、同文件名、相同 SHA-256 返回原 documentId / taskId；同键不同内容或名称返回 409。同键请求仍在处理时也会返回 409，稍后使用原键重试即可。不同用户或不同知识库可以独立使用同一键。重试时仍要求当前用户具备上传权限，不能凭幂等键绕过撤权。

PowerShell 7 示例，使用前面创建的 `$kb` 和登录返回的 `$login`：

```powershell
$uploadHeaders = @{ Authorization = "Bearer $($login.accessToken)"; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$file = Get-Item 'C:\实际路径\手册.pdf'
$task = Invoke-RestMethod "http://localhost:8080/api/knowledge-bases/$($kb.id)/documents" -Method Post -Headers $uploadHeaders -Form @{ file = $file }
Invoke-RestMethod "http://localhost:8080/api/document-tasks/$($task.taskId)" -Headers $headers
```

重试同一上传时复用 `$uploadHeaders`，不要重新生成幂等键。不要手动设置 multipart Content-Type，客户端会生成带 boundary 的正确请求头。

`GET /api/document-tasks/{id}` 可由当前知识库成员读取，返回 taskId、documentId、knowledgeBaseId、documentName、status、stage、attempts、errorCode、createdAt、updatedAt；时间按 UTC 表示。它不返回原文、存储路径或内部错误堆栈。任务不存在、用户无权访问、知识库或文档已删除均返回 404。

文件默认保存到项目下 `.data/documents`（已忽略 Git），可用 `DOCUMENT_STORAGE_DIRECTORY` 更改。磁盘文件采用随机 UUID 名称，先写临时文件再原子移动；数据库中保存原始名称、大小和 SHA-256。该目录需要持久化，备份数据库时也要备份文件；尚未提供下载接口或容器共享卷部署。

document、task、outbox 在同一个 MySQL 事务中创建；已知回滚会清理本次文件，重复上传也清理多余副本。若进程崩溃或数据库提交结果未知，会保守保留文件以避免数据丢失，可能留下孤立文件；自动对账回收尚未实现，不能把没有核对数据库的文件直接删除。

Outbox 记录 `DOCUMENT_UPLOADED` 事件，payload 只含 taskId 与 indexVersion，初始状态为 PENDING。这使上传不依赖 Kafka 可用性；后台发布器取得 broker 确认后才标为 PUBLISHED。

## Kafka 发布与独立 Worker

默认 `api` profile 启动 HTTP 服务与 Outbox 发布器；同一个应用包以 `worker` profile 单独启动时不监听 HTTP，只消费任务。在第二个项目根目录终端运行：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=worker"
```

或者先打包，再运行 `java -jar target/KnowFlowAI-0.0.1-SNAPSHOT.jar --spring.profiles.active=worker`。IDEA 可复制原运行配置，将新配置命名为 `KnowFlow Worker`，程序参数填 `--spring.profiles.active=worker`，JRE 仍选 25，工作目录仍为项目根目录。API 配置保持默认或显式指定 `api`，不要同时启用两个 profile。两个进程连接同一 MySQL 和 Kafka；后续解析还需共享文档存储目录。Worker 不需要 JWT 密钥。

Kafka 默认地址为 `localhost:19092`，可用 `KAFKA_BOOTSTRAP_SERVERS` 覆盖；修改 Compose 的 `KAFKA_PORT` 后也须同步修改此地址。Compose 使用单节点 KRaft、PLAINTEXT，仅绑定本机端口；topic 为 3 分区、1 副本，用于本地开发，不具备多节点容灾能力。

发布器每秒轮询，使用 `FOR UPDATE SKIP LOCKED` 与 60 秒租约取得事件，网络发送不占用数据库事务。发送失败按 2、4、8 秒递增退避，最长 300 秒；进程崩溃后可接管过期租约。写回结果须匹配租约令牌，避免旧发布者覆盖新状态。broker 确认后数据库写回失败可能重发，因此采用至少一次投递，并由 Worker 幂等处理。

V4 迁移增加接收时间和发布租约令牌。Worker 将首次接收的任务标为 `stage=QUEUED` 并写入 `received_at`，数据库事务提交后再提交消费位点。重复消息不重置状态、接收时间或处理次数。任务查询增加 `receivedAt`，未接收时为空。此时 `status=PENDING`、`attempts=0` 是预期结果；本轮尚未执行解析。

数据库故障会每秒重试并阻塞对应分区的后续消费，恢复后继续，不能提前确认消息。格式错误、空消息、任务或版本不存在的事件进入 `knowflow.document.uploaded.DLT`；死信发送失败也会重试原消息。当前没有死信自动重放接口，修复消息前应先检查原因。

## 测试与打包

### Swagger 与演示脚本

启动 API 后访问 `http://localhost:8080/swagger-ui/index.html`，OpenAPI JSON 位于 `/v3/api-docs`。注册与登录不需要令牌；登录获得 accessToken 后点 Authorize，填写令牌本身，不额外添加 Bearer 前缀。刷新页面后不持久保存授权。文档页面匿名可读，但业务接口仍执行原有鉴权。

接口文档使用 `springdoc.api-docs.version=OPENAPI_3_0`。当前依赖组合的默认 3.1 文档生成路径会出现 JsonSchema 类型转换警告；使用 [springdoc 官方版本配置](https://springdoc.org/properties) 后警告消失，无需调整业务 JSON 配置或降级 Spring Boot。契约测试覆盖 ID 正数约束、分页边界、搜索参数、文件二进制上传及响应字段类型。

上传接口选择 multipart 文件，并填写 Idempotency-Key。搜索接口填写 query、topK。服务部署时可通过 `SWAGGER_ENABLED=false` 关闭 API 文档及 UI；业务接口不受影响。

PowerShell 7 演示：

```powershell
# 只验证注册、登录、建库和上传，不要求模型服务已配置。
pwsh -File scripts/demo.ps1 -UploadOnly
# 已配置真实 Embedding，且 API、Worker、MySQL、Kafka、Qdrant 均运行时验证完整链路。
pwsh -File scripts/demo.ps1 -WaitSeconds 180
```

脚本每次创建独立 demo_ 账号与演示知识库，上传仓库内的公开示例文档，保留演示数据；不打印生成的密码或 JWT。完整模式轮询任务成功后执行搜索，失败或超时明确报错，不把仅上传成功当作完整链路成功。未配置模型时只能验证 UploadOnly 模式。

### 带权限过滤的向量检索

`POST /api/knowledge-bases/{id}/search`，Bearer JWT，请求示例：

```json
{"query":"如何申请访问权限？","topK":5}
```

query 非空且最多 2000 个字符；topK 默认 5，范围 1–20。成功返回数组，每项包含 chunkId、documentId、documentName、content、pageNumber、paragraphNumber、score；响应设置 Cache-Control: no-store。页码不存在时为 null，分数是 Cosine 相似度，不是答案置信度。

API 进程与 Worker 使用同一组 Embedding 和 Qdrant 配置，启用模型后重启两个进程。非成员或不可用知识库返回 404，包括系统 ADMIN；未登录返回 401。模型未启用、集合不存在或上游故障返回脱敏 503，有效集合没有可见结果则返回空数组。

检索前从 MySQL 授权，Qdrant 查询强制携带知识库过滤，模型调用后再次授权。正文来自 MySQL，仅返回当前成员可见、READY、匹配 active_index_version 与 vector_collection 的分块。最终读取使用短事务与知识库共享锁，和成员修改互斥；网络请求不占用数据库事务。授权以最终数据库读取为边界，已经发送的数据无法在后续撤权时追回。

每次最多取 200 个向量候选，验证并去重后返回前 topK 个；无效或旧版本点过多时可能少于 topK，不无限扫描。当前最多逐个校验 200 个候选，后续压测决定是否改为批量读取。真实百炼小样本链路已通过；尚未进行系统性效果评测。

### Hybrid Search

同一入口也支持 `{"query":"报销流程","topK":5,"mode":"HYBRID"}`，须同时启用 Embedding 与 BM25。两路各取最多 200 个候选，分别校验当前权限、READY 状态、激活版本，以及向量集合 / BM25 实例进度；两路校验与融合在同一数据库事务内完成。

HYBRID 的 score 为 `Σ 1 / (60 + rank)`，rank 从每路授权并去重后的第 1 名开始。同一分块在同一路只计一次、在两路出现则各计一次；融合后再截取 topK，同分按 chunkId 升序。原始向量分数与 BM25 分数不参与相加。空结果是合法的，任一路缺少配置或调用失败则返回 503，不静默伪装成完整 Hybrid。

### 可选 Rerank

本地默认 `RERANK_ENABLED=false`，普通搜索不会调用重排。配置好 `RERANK_URL`、`RERANK_API_KEY`、`RERANK_MODEL` 后启用服务，再请求 `{"query":"报销流程","topK":5,"mode":"HYBRID","rerank":true}`。当前适配 [百炼原生文本排序协议](https://help.aliyun.com/zh/model-studio/text-rerank-api)，默认模型 `gte-rerank-v2`；它不是聊天模型，也不是通用 OpenAI Chat Completions 协议。默认北京地址已通过一次短请求验证，新工作空间也可填写控制台提供的完整地域 URL。

只发送 RRF 前 20 个已授权片段（`RERANK_CANDIDATES` 可设 20–50）。总等待期限默认 `RERANK_TIMEOUT=PT5S`，覆盖响应体读取；超时取消请求、不自动重试。接收阶段限制响应为 128 KiB。HTTP 错误、超大响应、非法分数、重复 / 越界索引或不完整结果均退回 RRF 顺序。模型只决定候选顺序与分数，正文和引用位置仍来自 MySQL，返回前再次验证权限和当前激活版本。

响应体仍是结果数组，响应头 `X-Rerank-Status` 的取值如下：

| 状态 | 含义 |
|---|---|
| NOT_REQUESTED | 请求未开启重排 |
| DISABLED | 本地未启用重排服务，返回 RRF |
| INSUFFICIENT_CANDIDATES | 少于两个候选，跳过无必要的调用 |
| APPLIED | 配置的重排服务成功返回完整有效排列 |
| FALLBACK | 重排调用失败，返回原 RRF 顺序并过滤已失效片段 |

`X-Search-Score-Type` 标明 VECTOR / BM25 / RRF / RERANK；仅 APPLIED 返回 `X-Rerank-Model`。真实评测必须检查这些响应头，不能将关闭、跳过或回退结果计入 Hybrid + Rerank。当前仅验证过真实接口连通性，尚未证明质量提升或测量吞吐量。

### BM25 关键词检索

在本地 `.env` 设置 `BM25_ENABLED=true`，API 与 Worker 的 `BM25_DIRECTORY` 指向同一绝对目录（默认 `.data/lucene`，不同工作目录时必须显式指定）。启动 Worker 后会从 MySQL 中 READY 文档的当前激活分块逐步建立 Lucene 10.3.2 索引。新文档仍须完成原文档处理流程，关键词查询本身不调用 Embedding。

同一搜索接口传入 `{"query":"报销流程","topK":5,"mode":"BM25"}`。省略 mode 仍为 VECTOR，响应正文、文档名及引用位置格式相同。采用 CJKAnalyzer 与 BM25Similarity，关键词最多分析前 256 个 token，不支持用户指定 Lucene 查询语法。分数为 BM25 相关性，不是概率，也不能直接与向量分数相加。

Worker 是唯一 Writer，文件锁拒绝第二个 Writer。API 只读取已 commit 的磁盘索引；MySQL V10 `bm25_index_progress` 单独记录实例、版本、READY / CLEANED / RETRY_WAIT、提交时间及错误码。写入失败自动退避重试，间隔上限 300 秒。最终返回正文前再次校验权限、激活版本及该 Reader 实例的 READY 进度。向量激活到 BM25 同步完成之间可能暂时没有关键词结果。

删除立即在 MySQL 隐藏文档，BM25 Worker 随后提交空分块组完成物理索引清理。每次整组替换同一文档，重放和版本切换不会重复分块。索引丢失时，停下 Worker，将 API 与 Worker 配置到同一个新的空目录再启动；新实例会从 MySQL 重建，旧实例进度不会被误用。不要在运行时删除锁文件，也不要清空 MySQL 分块。损坏或未知来源索引启动失败时应先保留原目录排查。

当前限定同机共享持久化目录，不支持多个目录的 Worker 同时写同一数据库。API 每次查询打开已提交 Reader；后续以压测判断是否需要 SearcherManager。测试使用隔离的 MySQL 与临时磁盘目录，不读取或改写开发索引；测试通过不代表检索质量或吞吐量结论。

### Embedding 与 Qdrant

使用 Spring AI 2.0.1 的 OpenAI 兼容 Embedding 客户端，以及 Spring RestClient 调用 Qdrant REST API。Compose 与测试固定使用 Qdrant v1.18.2，数据卷为 qdrant-data，本地端口为 6333。健康检查仅探测 TCP 端口，实际写入仍检查 API 返回。

在本地 `.env` 配置以下项目，不要提交密钥；服务须支持 OpenAI `/v1/embeddings` 协议，并接受文本数组：

```properties
EMBEDDING_ENABLED=true
EMBEDDING_BASE_URL=https://你的服务地址/v1
EMBEDDING_API_KEY=你的本地密钥
EMBEDDING_MODEL=服务提供的实际模型名
EMBEDDING_DIMENSIONS=模型实际输出维度
QDRANT_URL=http://localhost:6333
```

模型与维度必须匹配。这里的维度用于验证返回值和创建集合，不要求服务支持可变维度参数。未启用时 API 与 Worker 正常运行，任务停在 CHUNKED。配置后重新启动 Worker 即处理已有分块。不要将通用聊天模型名填入 Embedding 配置。已通过百炼 text-embedding-v4 的小样本真实联调，见 [联调记录](docs/live-validation.md)。

V7 增加 `vector_cursor`、`vector_attempts` 与 `vector_collection`。Worker 每次领取最多 16 块，校验返回数量、维度、有限值与非零向量，使用 `wait=true` 确认 Qdrant 写入，再提交进度。未完成批次回到 PENDING / CHUNKED；全部完成才写 SUCCEEDED / INDEXED，并将 document 设为 READY、激活 active_index_version。此前章节描述的 CHUNKED 是向量处理关闭时的终点。

每批采用 90 秒租约；模型请求超时 20 秒，SDK 内部重试关闭；Qdrant 每次 HTTP 请求超时 10 秒。失败后 10 秒重试，同批最多 3 次，成功批次将该计数归零，崩溃也计入预算。解析器只领取 PARSING 阶段的重试任务，不会把向量重试误当作重新解析。旧令牌不能推进进度；同文档、版本、分块使用稳定 point ID，未知提交结果后的重放覆盖同一点。

集合名称由服务地址、模型名和维度生成，已有任务绑定集合，配置不同的 Worker 不接管这些任务。变更配置后需要恢复原配置处理旧任务，或在旧任务终止后调用重新处理接口创建新版本。相同模型别名背后的服务模型变更无法自动识别，需要由部署方保持模型版本稳定。已存在的集合必须维度一致且使用 Cosine；不会覆盖或删除旧集合。

Qdrant payload 保存知识库 ID、文档 ID、分块 ID、索引版本、文档名称及引用位置，正文保留在 MySQL。部分写入或失败遗留点不等于可检索内容，后续检索必须重新检查 MySQL 中的 READY、active_index_version、vector_collection 与当前用户权限。向量搜索已执行这些校验；尚未实现孤立向量自动清理。

自动测试使用本地 HTTP 服务返回固定测试向量，验证真实 Spring AI 客户端的协议、真实 Qdrant 写入和真实 MySQL 状态机；固定向量没有语义能力，不是模型质量测试或检索评测。真实模型的 Token、成本与检索质量统计将在后续增量实现。

### TXT / Markdown 异步处理

Worker 内的 `TextTaskProcessor` 每秒领取一个已接收文档任务，通过 `DocumentParser` 处理四种文件。V5 创建 `document_chunk`，唯一键为 `(document_id, index_version, chunk_index)`，保存正文与从 1 开始的段落编号。API 上传仍不执行解析。

状态流转为 `PENDING / QUEUED → PROCESSING / PARSING → PENDING / CHUNKED`。CHUNKED 表示分块已落库、等待 Embedding；文档仍为 PROCESSING，active_index_version 为空，不标为 READY 或 SUCCEEDED。上文的 QUEUED 接收状态对文本只是中间状态。

处理先检查文件 SHA-256，再严格按 UTF-8 解码，去除文件开头 BOM、统一换行，按空行编号段落；Markdown 标记原样保留。每块最多 800 个 Unicode 码点，不切断 emoji 代理对，同段多块共享段落号。暂不做重叠块或 Markdown 语法分析；最多 20000 块，后续由真实检索评测决定策略。此编号是当前解析规则下的段落号，不是页码。

60 秒租约允许崩溃恢复，数据库令牌与事务保证旧执行者不能覆盖新结果；分块写入失败会整体回滚，租约到期后再试。文件读取失败进入 RETRY_WAIT，10 秒后重试，最多 3 次；摘要不匹配或空正文直接 FAILED。耗尽崩溃重试预算也会 FAILED。没有自动续租，超时任务可能重复计算，但写回受令牌保护。重试不需要重新上传或重新发送 Kafka 消息。

文本解析复用 JDK、Spring 调度与 JDBC。尚未提供分块正文查询接口；后续检索必须通过知识库权限检查后才能返回正文。

### PDF / DOCX 异步处理

PDF 使用 [Apache PDFBox 3.0.8](https://pdfbox.apache.org/download)，DOCX 使用 [Apache POI 5.5.1](https://poi.apache.org/download.cgi)，沿用相同任务、租约、摘要校验与事务写入流程。V6 为 `document_chunk` 新增可空 `page_number`，旧数据不改写。

PDF 按物理页提取文字，每页独立分块，页码从 1 开始；空白页不生成块，但后续页码不压缩。段落编号在各页内重新计数。文件最多 1000 页，提取正文最多 10 Mi 个 Java 字符单元。拒绝加密 PDF；没有可提取文字的扫描件或空白文档进入 FAILED，不自动运行 OCR。混合扫描页与文字页的 PDF 仅提取已有文字的页面。

DOCX 按正文顺序提取段落与表格单元格，空段落占用段落号但不生成块，表格中段落沿用全局编号；不猜测 Word 排版页码，page_number 为 NULL。支持最多 20 层表格嵌套、10 Mi 个 Java 字符单元正文，沿用上传阶段 32 MB 解压与 2000 ZIP 条目限制及 POI 的 ZIP 安全检查。不提取页眉页脚、批注、脚注、文本框或图片文字，也不保留表格二维结构。

四种类型均最多 20000 块。文件读取故障仍按原策略重试；格式损坏、正文为空、加密或解析超限归为 INVALID_CONTENT，直接 FAILED，不写入部分分块。正文输出有上限，但 PDF / Office 内部对象解析仍在 Worker JVM 内执行，当前不提供硬超时或独立解析沙箱。

```powershell
.\mvnw.cmd clean verify
& "$env:JAVA_HOME/bin/java.exe" -jar target/KnowFlowAI-0.0.1-SNAPSHOT.jar
```

测试使用 Testcontainers 自动启动、清理独立的真实 MySQL 8.4 与 Kafka 4.3.1 临时容器，使用随机端口和临时数据库，不依赖 `.env` 密码，不操作 Compose 开发库。首次运行会下载镜像；Docker 不可用时测试失败，不静默跳过、不用 H2 替代。

覆盖 Flyway 迁移、健康检查、管理端点隔离、注册后查库、随机盐与密码匹配、大小写并发冲突、非法 JSON / 字段、越权设置角色、密码字节上限及用户名长度。鉴权测试进一步覆盖 JWT 签发、过期、伪造、错误 issuer / audience、缺少必需声明、禁用和删除账号、动态撤销角色、数据库故障拒绝放行。测试密钥固定为公开测试值，不读取开发密钥。报告位于 `target/surefire-reports/`，这些是功能测试，不是压测指标。

知识库测试覆盖原子创建、失败回滚、三个角色的读写边界、系统管理员不绕过成员权限、降级与撤权对旧 JWT 生效、并发幂等授权、OWNER 保护、权限范围内分页、参数校验与删除态隔离。

上传测试使用真实 HTTP multipart、真实 MySQL 与独立临时文件目录，覆盖四种文件、摘要与落盘字节一致性、并发和重复上传、幂等键冲突与作用域、权限和任务隔离、类型伪装、压缩炸弹、上传大小限制、存储故障以及 Outbox 写入失败时的数据库/文件回滚。

消息测试覆盖 HTTP 上传到 Worker 接收、重复投递、过期租约接管、发布器并发抢占、暂停真实 broker 后的退避重试、非法消息转死信，以及数据库故障时不提交消费位点。文本处理覆盖分块落库、重复消息不重复处理、文件丢失与恢复、摘要篡改、崩溃租约接管及重试耗尽，并验证段落编号与 Unicode 分块。PDF / DOCX 测试使用库生成的真实文件，覆盖上传到分块入库、PDF 空页后的页码保留、DOCX 段落表格顺序，以及加密、损坏、无正文和 PDF 页数超限。向量测试进一步覆盖分批激活、稳定 ID 重放、模型错误与维度错误、Qdrant 暂停恢复、MySQL 提交失败后的安全重放。最新完整 `verify` 结果见 [实施进度](docs/roadmap.md)；这些结果不代表吞吐量或检索效果。

### 同步证据问答

在本地 `.env` 配置 `CHAT_ENABLED=true`、`CHAT_API_KEY`、`CHAT_BASE_URL` 和 `CHAT_MODEL`。百炼使用 `https://dashscope.aliyuncs.com/compatible-mode/v1` 与 `qwen3.8-flash`，密钥可引用已有 `${BAILIAN_API_KEY}`。默认关闭思考、最多 512 输出 Token、超时 20 秒、无自动重试。示例文件默认不启用付费模型。

登录并在 Swagger 授权后调用 `POST /api/knowledge-bases/{id}/answers`：

```json
{"question":"报销需要提交什么材料？","topK":4,"mode":"HYBRID","rerank":false}
```

默认 HYBRID 需要启用 Embedding 与 BM25，首次建立索引需等待 Worker 完成。已有 BM25 索引时可选 `mode=BM25`，避免查询 Embedding 调用。无证据时返回 `INSUFFICIENT_EVIDENCE`，不会调用聊天模型。成功结果包含 `answer`、服务端校验的 `citations`（文档名、页码或段落、连续原文摘录）、实际模型及 `usage`；服务未提供 usage 时为 null。

引用编号伪造、摘录不匹配或输出截断返回 502；模型不可用返回 503。生成期间撤权返回 404，输入文档失效返回 409，均不返回生成正文。这些来源校验不等同于语义正确性评测。完整协议见 [问答设计](docs/answer-design.md)。

### SSE 问答

`POST /api/knowledge-bases/{id}/answers/stream` 使用相同 JSON 请求、Bearer JWT，响应为 `text/event-stream`。可以通过支持流式响应的 HTTP 客户端或 `curl.exe -N` 查看；浏览器使用带 Authorization 的 fetch 读取流，并用 AbortController 取消请求。

依次处理 metadata、delta、citation、usage、done。delta 为未完成正文，只有 done 才能标记完整；error 或连接中断不能标记成功。done.answer 是最终文本，应覆盖临时正文；error.discard=true 时清除临时文本。原生 EventSource 无法直接发送此 POST 和 Bearer 请求头，不适用于该接口。

默认流式总期限 30 秒，断线心跳间隔 1 秒；取消或超时会释放上游订阅，不自动重试。详情与字段见 [SSE 协议](docs/sse-design.md)。此增量未调用真实百炼流式接口，自动测试使用本地模型协议替身。

### 会话与历史记录

答案请求可增加 `conversationId` 续问；省略时自动建会话。会话绑定当前用户与知识库，不能跨库或借用别人的会话。同一会话正在生成时，再次请求返回 409。同步响应头返回 `X-Conversation-Id` / `X-Message-Id`，SSE metadata 返回 `conversationId` / `messageId`。

`GET /api/conversations?afterId=0&limit=50` 查询本人可访问的会话；`GET /api/conversations/{id}/messages?afterId=0&limit=50` 查询消息。每页最多 100 条，响应禁止缓存。助手消息区分 RUNNING、COMPLETED、FAILED、CANCELLED；失败和取消可能保留未完成文本，不能作为完整答案展示。

历史消息使用过的任一来源被删除、换版或修改时，返回 `redacted=true`、`content=null` 和空引用；知识库撤权后整个会话返回 404。超过五分钟仍运行的遗留消息在读取或续问时标记 PROCESS_INTERRUPTED，后续统一对账补充后台巡检。COMPLETED 表示服务已完整生成并持久化，不代表客户端已经确认收到了最后一个数据包。

表结构和边界见 [会话设计](docs/conversation-design.md)。

### 上下文查询改写

在续问请求中显式设置 `rewrite=true`，服务读取最近一轮完整且仍有权限的问答，把追问改写为独立检索问题。默认不改写，首次提问或无有效历史不会调用改写模型。同步和 SSE 都支持：

```json
{"question":"这个该找谁办理？","conversationId":123,"mode":"HYBRID","rewrite":true}
```

改写复用当前聊天模型，最多 128 输出 Token、3 秒超时、不重试，失败退回原问题。历史消息返回 `originalQuestion`、`retrievalQuery`、`rewriteStatus`；改写 usage 单独保存在数据库，不能与回答 usage 混为一次调用。SSE metadata 包含 rewriteStatus。即使模型输出其他知识库相关的文字，也只检索请求绑定的知识库。

改写引用的历史消息会形成来源依赖；任一历史来源失效时，衍生答案和 retrievalQuery 一并隐藏。当前依赖检查受 MySQL 递归上限约束，超出时拒绝读取。测试替身上的检索对照只验证链路，尚无真实改写质量提升结论。详情见 [查询改写设计](docs/rewrite-design.md)。

### 模型超时与备用模型

聊天调用配置 `CHAT_CONNECT_TIMEOUT`（默认 5 秒）、`CHAT_TOTAL_TIMEOUT`（默认 30 秒）、`CHAT_FIRST_TOKEN_TIMEOUT` / `CHAT_IDLE_TIMEOUT`（默认各 8 秒）。流式接口同时受原有 `CHAT_STREAM_DEADLINE` 约束。`CHAT_RETRIES` 默认 0、最多 2 次，SDK 内部重试始终关闭，避免叠加次数。

需要备用聊天模型时设置 `CHAT_FALLBACK_ENABLED=true` 与实际可用的 `CHAT_FALLBACK_MODEL`。默认复用主模型服务地址和密钥；不同服务使用 `CHAT_FALLBACK_BASE_URL` / `CHAT_FALLBACK_API_KEY`。当前本地未启用备用模型，未为此额外调用真实 API。

仅瞬态错误可重试，主模型次数耗尽后最多调用一次备用模型；认证错误或内容校验失败不重试。SSE 一旦产生正文或结束标记，后续失败保留未完成状态，禁止切换。总期限不会因重试或切换重置；最终 model / usage 来自实际返回结果的模型。Embedding 不切换模型，改写仍单次失败退回原问题。详见 [模型韧性设计](docs/model-resilience-design.md)。

### 模型调用统计

管理员可通过 `GET /api/admin/model-calls` 分页查看模型调用尝试，通过 `/api/admin/model-calls/summary` 查询最近 24 小时的次数、耗时和已知 Token。每次重试独立记录，流式累计 usage 不重复相加；缺失用量保持未知。调用完成不代表回答引用校验通过。

当前覆盖聊天回答（CHAT）、查询改写（REWRITE）、向量生成（EMBEDDING）和重排（RERANK）；Embedding 区分 QUERY / INDEX，入库调用关联 taskId。内容校验失败仍保留已经发生的调用和已知用量。Rerank 只返回总 Token 时，输入、输出保持未知。

匹配北京百炼价格的新调用会保存价格快照，管理员可查看 estimatedCost 及汇总 estimatedCostCny / unknownCostCalls。这是公开标价估算，未匹配价格或用量不足时保持未知，不代表实际账单。预算拦截待接入，真实联调继续暂停。边界见 [调用记账设计](docs/model-call-design.md)、[成本设计](docs/model-cost-design.md)，完整进度见 [实施进度](docs/roadmap.md)。
