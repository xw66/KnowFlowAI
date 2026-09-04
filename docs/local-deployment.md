# 本地一键部署

需要 Docker Desktop 的 Linux 容器环境。复制 `.env.example` 为 `.env`，填写数据库密码和至少 32 个随机字节的 Base64 JWT 密钥。模型密钥仅保存在本地 `.env`，不放进前端构建变量。

启用实际问答时填写兼容服务配置，设置 `EMBEDDING_ENABLED=true`、`BM25_ENABLED=true`、`CHAT_ENABLED=true`。百炼北京示例：Embedding 地址 `https://dashscope.aliyuncs.com/compatible-mode/v1`，模型 `text-embedding-v4`，维度 `1024`；聊天模型使用已开通的 `qwen3.8-flash`。保持 `MODEL_BUDGET_ENABLED=true`，未知价格或预算不足会阻止调用；不要通过关闭预算来绕过真实服务计费限制。

```powershell
docker compose --profile app up -d --build --wait --wait-timeout 180
```

打开 http://127.0.0.1:8088 ，Swagger 在 http://127.0.0.1:8088/swagger-ui/index.html 。不需要安装 Node 或启动 Vite。所有宿主机端口仅绑定回环地址；这是本机演示部署，不是公网配置。

- web 提供 Vue 静态文件，并代理 API、Swagger 与 SSE；代理关闭响应缓冲和 POST 自动重试。API 容器更换地址后通过 Docker DNS 重新解析。
- API 的 Actuator 健康检查验证 HTTP、数据库和 Redis；Worker 在容器内部回环地址启动相同检查，不发布端口。健康状态不等同于所有文档已经索引完成，入库结果以任务状态和实际检索为准。
- MySQL、Redis、Kafka、Qdrant、文档文件和 Lucene 使用命名卷。API 与 Worker 使用同一应用镜像，仅运行配置不同。
- 匿名注册和登录仍按服务端看到的来源地址限流，经本地代理的请求共享来源额度；已登录检索、问答按用户限制。不信任客户端自行提供的 Forwarded 头。
- 缓存、幂等、限流、重排超时、聊天超时、备用模型与预算开关均从 `.env` 传入容器。`REDIS_PORT` 表示宿主端口，容器内部始终连接 Redis 的 6379。

## 保留数据重启

```powershell
docker compose --profile app stop
docker compose --profile app up -d --wait --wait-timeout 180
```

重新登录后原知识库、文档、索引和会话应保留。`down` 默认也保留命名卷；不要使用 `down -v` 清理正常项目。数据库初始密码只对空卷生效，改 `.env` 不会重置已有数据库密码。

## 隔离部署验收

`scripts/compose.acceptance.yaml` 是专用测试覆盖文件，使用固定模型协议替身，并仅在该环境关闭预算校验。它不连接百炼，结果不能用作模型效果或性能结论。

验收脚本需要本机 Node 24。创建忽略目录 `target` 下的 `compose-acceptance.env`，填写随机 `DB_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`JWT_SECRET`，另设以下端口，避免影响正常数据卷：

```dotenv
WEB_PORT=18088
APP_PORT=18080
MYSQL_PORT=13307
REDIS_PORT=16379
KAFKA_PORT=29092
QDRANT_PORT=16333
```

```powershell
docker compose -p knowflow-acceptance --env-file target/compose-acceptance.env -f compose.yaml -f scripts/compose.acceptance.yaml --profile app up -d --build --wait --wait-timeout 180
node scripts/compose-acceptance.mjs
docker compose -p knowflow-acceptance --env-file target/compose-acceptance.env -f compose.yaml -f scripts/compose.acceptance.yaml --profile app stop
docker compose -p knowflow-acceptance --env-file target/compose-acceptance.env -f compose.yaml -f scripts/compose.acceptance.yaml --profile app up -d --wait --wait-timeout 180
node scripts/compose-acceptance.mjs --resume
```

脚本通过 Nginx 检查注册、登录、上传、三种检索、SSE 引用及会话恢复；替身在正文中间延迟一秒，断言客户端在结束前已收到正文。状态文件含临时测试账号密码，保存在忽略的 `target` 内；结果文件不含密码或令牌。

在内存有限的机器上，先完成并停止隔离 Compose，再运行 Maven 完整回归，不同时启动多套测试数据库。Surefire 使用 Spring 原生 `spring.test.context.cache.maxSize=2`，使不再使用的测试上下文及其容器及时关闭；该设置不改变生产行为或减少测试用例。

代理行为依据 [Nginx proxy 模块文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)；健康端点采用 [Spring Boot Actuator](https://docs.spring.io/spring-boot/how-to/actuator.html)。
