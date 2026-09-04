# KnowFlow Web

Vue 3 + TypeScript，原生 fetch 与 SSE，不使用 UI 组件库。API 保持 `/api` 同源路径。

## 启动

在仓库根目录先启动后端，再运行：

```powershell
npm --prefix frontend ci
npm --prefix frontend run dev -- --host 127.0.0.1
```

打开 http://127.0.0.1:5173/。开发代理默认连接 http://localhost:8080。需要改地址时，在启动前设置 `KNOWFLOW_API_TARGET`。生产部署须将 `/api` 反向代理到后端；不要把模型密钥设置成 Vite 环境变量。

## 操作

1. 注册或登录。用户名 3–64 位英文字母/数字/下划线；密码 8–72 字符且 UTF-8 不超过 72 字节。
2. 左侧创建/选择知识库。「文档」页上传资料，处理状态自动刷新；处理成功后可检索。
3. 「问答与搜索」中选择智能问答或查找原文。问答支持停止、历史恢复和续问；引用可以展开。
4. 「成员与设置」修改名称或授权。VIEWER 不显示写入口；成员管理仅 OWNER 可用。用户 ID 在侧栏底部查看。

## 验证

```powershell
npm --prefix frontend test
npm --prefix frontend run build
```

Node 24 原生测试覆盖 API 鉴权/错误、UTC 时间、SSE UTF-8/CRLF 分帧、提前结束、来源撤回及取消。会话测试通过 Vue 宿主生命周期验证重复提交、卸载取消、迟到响应隔离、续问与引用状态，不会调用真实模型。

独立浏览器协议验收（与真实后端分开）：

```powershell
node frontend/tests/fixture.mjs
# 在另一个终端运行
$env:KNOWFLOW_API_TARGET='http://127.0.0.1:18080'
npm --prefix frontend run dev -- --host 127.0.0.1 --port 5174
```

仅用于测试的账号 `ui_test`、密码 `UiTestPass123!`。问题含「缓慢」用于取消、含「中断」用于断流、含「撤回」用于来源失效；原文搜索输入「过期」返回 401。此服务只在回环地址监听，不连接数据库、Qdrant 或模型，示例数据不能用作效果评测。正常开发不要设置此测试代理地址。
