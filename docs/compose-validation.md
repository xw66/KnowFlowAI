# Compose 部署验收记录

日期：2026-09-04。环境：Windows、Docker Desktop Linux containers、JDK 25；Docker 虚拟机可用配置约 7.5 GB 内存、2 GB swap。

## 隔离空库与重启

使用独立项目 `knowflow-acceptance-final`、独立命名卷和 18088 前端端口，按 `scripts/compose.acceptance.yaml` 连接本地协议替身。最终空库运行与保留数据重启均返回 PASS：

- 注册、登录、创建知识库、上传 Markdown，通过 Kafka Worker 处理至 SUCCEEDED。
- 经 Nginx 的 VECTOR、BM25、HYBRID 查询均返回刚上传的文档正文。
- SSE 返回引用、完成事件并持久化会话；重启后重新登录仍能读取文档、检索结果和历史引用。
- 替身在正文中间等待 1000 ms；客户端首段正文为 391.904 ms，完成事件为 1345.178 ms，满足“结束前收到正文”的断言。这些时间只验证代理流式行为，不是实际模型性能数据。

原始脱敏结果：`target/compose-fresh-result.json`、`target/compose-restart-result.json`。完整启动与重启日志：`target/compose-final-fresh-start.log`、`target/compose-restart.log`。包含临时账号密码的状态文件保留在忽略目录，不提交。

## 百炼真实连通性

正常项目通过 `http://127.0.0.1:8088` 执行 `scripts/demo.ps1 -Stream`，完成真实 Embedding、入库、查询和 SSE；收到 metadata、citation、done。日志为 `target/compose-live-demo.log`。

浏览器直接访问生产构建，验证登录、刷新后保持登录、打开已有会话并展开 demo.md 段落 2 原文引用；浏览器 error 日志为空。这些只读页面检查未增加模型调用。

联调前模型预算账本累计估算支出为 0.0016148 CNY，联调后为 0.0018813 CNY；总限额仍为 20 CNY，预留为 0，未触发停用。该账本为标价估算，不代表供应商账单，也不构成检索效果结论。

## 验收中修正的问题

- 补齐 `MODEL_BUDGET_ENABLED` 到应用属性的绑定；生产默认 true，协议替身环境显式 false。
- 替身 Embedding 响应补齐 SDK 必需的 usage 字段；占位用量只用于测试协议，不作为真实用量。
- 完整回归与两套 Compose 并行时耗尽 Docker 内存与 swap。已停止并改为串行执行；Surefire 限制 Spring 上下文缓存为 2，及时释放旧测试数据库。
- Docker 恢复时遭遇 Windows 残留 AF_UNIX socket 错误。停止 Desktop 后备份重命名临时 `Docker/run` 和仅含 `engine.sock` 的运行目录，随后正常启动；未恢复出厂设置，正常项目的数据卷保留且真实链路验证通过。

本次未完成真实检索质量评测、压测、备用模型和完整外部索引对账；这些仍在后续计划内。

最终串行回归：JDK 25 Maven verify 252 项全部通过（无跳过），日志 target/compose-final-regression.log；前端 13 项测试通过，生产前端镜像构建通过。验收环境停止后正常项目七个服务全部 healthy。
