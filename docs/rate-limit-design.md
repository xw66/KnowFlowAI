# 原子接口限流

默认启用 RATE_LIMIT_ENABLED=true，要求 Redis 可用。沿用 Spring Data Redis，不新增依赖。配置错误在启动时拒绝：窗口 1 秒至 1 小时，各类次数 1 至 1000000。

| 配置 | 默认 | 范围 |
|---|---|---|
| RATE_LIMIT_WINDOW | PT60S | 所有窗口长度 |
| RATE_LIMIT_REGISTER | 5 | 注册，同一来源 IP |
| RATE_LIMIT_LOGIN | 20 | 登录，同一来源 IP |
| RATE_LIMIT_SEARCH | 60 | 搜索，同一用户，跨知识库共用 |
| RATE_LIMIT_ANSWER | 20 | 问答，同一用户，同步与 SSE 共用 |

窗口从某个桶第一次请求起算；拒绝不延长 TTL，也不继续递增计数。Lua 原子完成计数和过期设置，Redis 负责时间，API 进程没有本地计数器。窗口边界可能有双倍瞬时突发；当前控制请求频率，不保证同时进行的请求数量。

MVC 拦截器在 Spring Security 鉴权后、请求体解析和 Controller 调用前执行，按实际 HandlerMethod 分类，不用用户提交的路径字符串判断类别。无效参数也占用次数，未通过 JWT 校验的搜索/问答不占用用户额度。知识库授权继续由原业务层执行。GET、其他 Controller 和无映射的请求不占用额度。

仅 REQUEST 分派计数一次；ASYNC 和 ERROR 跳过，SSE 完成重新分派不会再次计数。使用 Servlet 的来源地址，server.forward-headers-strategy=none，不信任 Forwarded 或 X-Forwarded-For。当前未配置受信反向代理；同一 NAT 出口的注册/登录共享额度。

超限响应为 HTTP 429、application/problem+json，Retry-After 为剩余窗口向上取整的秒数，并设置 no-store。Redis 异常、命令超时或损坏计数返回脱敏 503，不能直接放行。没有 TTL 的计数会在同一个 Lua 脚本内修复过期时间。503 可能已经消耗一次 Redis 计数（网络结果未知），不自动重试。

普通缓存故障仍允许回源；限流故障策略更严格。由于默认限流要求 Redis，Redis 健康检查默认启用；只有同时停用缓存与限流时，才可显式配置 management.health.redis.enabled=false。

RateLimitTests 使用隔离 MySQL/Redis 和真实 HTTP，验证并发、窗口过期、转发头伪造、用户隔离、跨库共享、同步/SSE 共享、损坏计数、ACL 故障、超时及配置边界。VectorTests 在启用限流的条件下执行已有模型协议测试，并断言真实 SSE 生命周期只计数一次。没有使用付费模型或生成性能结论。

依据：[Redis 原子计数与限流](https://redis.io/docs/latest/commands/incr/)、[Spring MVC 异步分派](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/AsyncHandlerInterceptor.html)。
