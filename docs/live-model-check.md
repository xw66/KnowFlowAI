# 真实模型联调

## 重排与查询改写

运行 `node scripts/live-retrieval-check.mjs` 前启用本地 `RERANK_ENABLED=true`，填写同一百炼服务的重排密钥，保持模型预算保护开启。脚本创建三篇合成文档，执行真实上传、检索、重排和一次上下文追问；不包含真实企业资料。

2026-09-04 实测：`gte-rerank-v2` 返回 APPLIED。追问“这项审批多久能完成？”被改写为“差旅报销由直属主管审批多久能完成？”，改写状态 APPLIED。使用同一语料和检索模式，原始问题与改写问题的目标片段均排第 1：本例没有体现排名提升，不据此宣称检索质量改善。

原始结果保存在 `target/live-retrieval-check.json`，包含源文档、两组候选、实际模型回答及引用，不含密钥、密码和 JWT。正式质量结论仍需后续版本化评测集。

## 备用模型验证方法

备用模型使用百炼北京的 `qwen-turbo`，明确关闭思考模式。V20 保存价格快照：输入 0.3 元/百万 Token，输出 0.6 元/百万 Token，最大输入 98304 Token；依据 [百炼模型说明](https://help.aliyun.com/zh/model-studio/qwen-turbo)。真实费用仍以供应商账单为准。

普通 Maven verify 不执行 `LiveFallbackIT`。在正常应用启动并完成 V20 迁移后，显式执行：

```powershell
.\mvnw.cmd '-Dtest=LiveFallbackIT' '-Dknowflow.live=true' test
```

测试读取本地 `.env`，使用现有 MySQL 的同一模型预算账本。主模型故障是人工注入的 TimeoutException，不发送外部请求，也不在真实账本伪造主模型用量。备用调用是真实百炼请求并正常预留、结算预算；验证首正文前切换，以及正文输出后故障不再触发备用请求。结果写入 `target/live-fallback.json`。

该方法验证故障注入与真实备用调用的组合，不声称百炼实际发生过故障，也不作为压测或稳定性统计。

## 2026-09-04 验收记录

- 完整 `verify`：252 项通过；显式 `LiveFallbackIT`：1 项通过。应用与 Worker 已完成 V20 迁移并健康启动。
- 真实备用响应：差旅报销审批应在三个工作日内完成。[C1]；输入 49、输出 14、合计 63 Token，调用账本耗时 1179 ms，估算 0.0000231 元，预占已结算。正文后故障保留部分输出，没有再次调用备用模型。
- 初次测试误将 `.env` 中的变量引用当作密钥，收到 401；现使用 Spring 原生占位符解析。该失败记录的用量未知，保留 0.5359296 元预占，不将其当作真实费用或擅自归零。
- 核验时共享预算上限 20 元，累计估算支出 0.0030266 元，预占 0.5359296 元。真实账单与未知用量仍需供应商侧核验。
- 本地启用 `CHAT_FALLBACK_ENABLED=true`、`CHAT_FALLBACK_MODEL=qwen-turbo`，沿用主服务地址与密钥；默认环境模板仍关闭备用，使用者按需配置。

脱敏原始记录随仓库保存：[检索与改写](validation/2026-09-04-retrieval.json)、[备用调用](validation/2026-09-04-fallback.json)。检索记录中的文档 `status` 是上传响应时的 PENDING；脚本等待任务 SUCCEEDED 后才执行检索。
