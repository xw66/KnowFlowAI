# KnowFlow AI

企业知识治理与智能检索平台，面向 Java 后端与 AI 应用开发实践。目录、表设计、业务流程和验收计划见 [实施设计](docs/design.md)。

已实现：HTTP 健康检查、MySQL / Flyway 迁移、用户注册与登录、BCrypt 密码哈希、JWT 鉴权、系统 RBAC、知识库与成员权限、参数校验、唯一约束、ProblemDetail 错误响应及 JSON 结构化日志。

尚未实现：入口限流、文档上传与处理、Worker、检索及检索时的权限过滤、模型、Swagger 和完整应用 Compose。当前 Compose 仅包含 MySQL，接口用于本地开发验证。

## 环境与启动

- JDK 25，Spring Boot 4.1.1，Maven Wrapper 3.9.16。
- Docker Desktop 使用 Linux containers，须能成功执行 `docker info`。
- MySQL 8.4.8，Compose 与集成测试使用相同镜像版本。

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
docker compose up -d --wait mysql
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
- 密码：12–72 个 Java 字符单元，同时 UTF-8 编码不超过 72 字节；不修剪、不截断。BCrypt 使用独立随机盐，保存带 `{bcrypt}` 标识的哈希。
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

## 测试与打包

```powershell
.\mvnw.cmd clean verify
& "$env:JAVA_HOME/bin/java.exe" -jar target/KnowFlowAI-0.0.1-SNAPSHOT.jar
```

测试使用 Testcontainers 自动启动、清理独立的真实 MySQL 8.4 临时容器，使用随机端口和临时数据库，不依赖 `.env` 密码，不操作 Compose 开发库。首次运行会下载镜像；Docker 不可用时测试失败，不静默跳过、不用 H2 替代。

覆盖 Flyway 迁移、健康检查、管理端点隔离、注册后查库、随机盐与密码匹配、大小写并发冲突、非法 JSON / 字段、越权设置角色、密码字节上限及用户名长度。鉴权测试进一步覆盖 JWT 签发、过期、伪造、错误 issuer / audience、缺少必需声明、禁用和删除账号、动态撤销角色、数据库故障拒绝放行。测试密钥固定为公开测试值，不读取开发密钥。报告位于 `target/surefire-reports/`，这些是功能测试，不是压测指标。

知识库测试覆盖原子创建、失败回滚、三个角色的读写边界、系统管理员不绕过成员权限、降级与撤权对旧 JWT 生效、并发幂等授权、OWNER 保护、权限范围内分页、参数校验与删除态隔离。

下一增量：文件上传、文档任务与事务 Outbox；上传接口只创建任务，不同步解析。
