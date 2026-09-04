# 百炼小样本联调记录

验证日期：2026-09-04。仅使用仓库公开演示文档和简短连接提示，没有压测或大规模索引。

## 配置

- 北京地域 OpenAI 兼容接口：`https://dashscope.aliyuncs.com/compatible-mode/v1`。
- Embedding：`text-embedding-v4`，实际返回 1024 维；API 与 Worker 使用相同配置。
- 用户指定聊天模型：`qwen3.8-flash`。已验证短调用可用，关闭思考，探针输出上限 32 Token。
- 密钥只保存于已忽略的 `.env`，本文及测试代码不含密钥。CHAT_* 是后续问答模块的配置准备，目前尚未实现产品问答接口。

## 已验证

1. Embedding 探针成功，服务返回 prompt_tokens=9、total_tokens=9。
2. 聊天探针成功，服务返回 prompt_tokens=30、completion_tokens=2、total_tokens=32，finish_reason=stop。
3. 项目 API 注册、登录、创建知识库并上传 docs/demo.md。
4. 独立 Worker 经 Kafka 接收、提取分块，调用真实模型写入 Qdrant，任务完成并激活索引。
5. 使用同一真实模型查询，返回 demo.md 的原文和段落编号。
6. scripts/demo.ps1 最终复验成功，并检查返回正文属于本次上传文档；修复了 PowerShell JSON 数组外层包装导致结果展示为空的问题。
7. Swagger JSON、UI 页面及 JavaScript 资源通过 HTTP 自动测试；完整 Maven verify 通过 92 项测试。

## 费用与结论边界

两个独立连接探针的服务端 usage 合计 41 Token。随后进行两次短文档演示联调；当前应用尚未保存这些链路调用的 usage，因此 41 不是本次所有联调的总 Token 数，不能据此推算总费用。没有编造费用、延迟分位数或检索质量指标。

第一次演示存在脚本展示问题，第二次修复后验证了正文内容。两次各创建一个独立 demo_ 账号和知识库，数据保留。临时 API 与 Worker 已停止，MySQL、Kafka、Qdrant 保持运行。后续普通测试继续使用测试替身，不使用此密钥；真实模型仅在明确的联调或评测中调用。
