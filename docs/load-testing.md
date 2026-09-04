# 本地压测记录

使用 Node 24 脚本 `scripts/load-test.mjs`，在独立 Compose 项目中按并发 1、5、10 测试 HTTP 检索、SSE 问答和异步文档入库。脚本记录每类请求的错误率、吞吐量、P50/P95/P99、平均耗时和批次墙钟时间：

```powershell
$env:KNOWFLOW_LOAD_BASE_URL = 'http://127.0.0.1:18080'
node scripts/load-test.mjs
```

2026-09-04 使用本地协议替身运行，原始结果为 `docs/validation/2026-09-04-load-test-fixture.json`。HTTP 检索和异步入库三个并发档位均无错误；SSE 在并发 5、10 时分别出现 46.7% 和 80.0% 错误，低并发 1 时为 0%。该结果用于验证压测脚本和当前协议承载行为，不代表真实聊天模型性能；正式简历数字必须重新使用真实模型并记录硬件、数据规模、限流配置和外部模型耗时。
