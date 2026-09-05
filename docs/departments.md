# 部门与知识库开放范围

当前按一个部署对应一家公司设计。「全员」指该部署中所有已登录且启用的用户，不包括匿名访问。没有引入跨公司租户隔离。

- 管理员在侧栏「我的部门」创建部门。用户可自助加入、退出多个部门，无需审批。
- 系统管理员可查看全部知识库，并与知识库所有者一样维护开放范围和成员权限。
- 创建知识库和「成员与设置」均可选择开放范围：仅指定成员（默认）、全员只读、指定部门只读。
- 指定部门可多选，命中任一个即获得只读权限，包括文档、处理任务、搜索与问答。
- 单独授权优先：OWNER > EDITOR > VIEWER。部门和全员共享不会赋予编辑权限。
- 退出一个部门后，如仍命中其他部门、全员共享或单独授权，继续保有访问权限。移除单独成员也不会覆盖共享授权。
- 部门归属和共享范围实时从数据库读取；历史对话仍只属于创建该对话的用户，且读取时重新验证知识库权限。
- 自助加入适合公司内部共享，不是保密审批。需要部门准入审核时再增加申请和审批流程；敏感资料可继续使用「仅指定成员」。

## API

| 方法与路径 | 用途 |
| --- | --- |
| GET /api/departments?afterId=0&limit=100 | 部门目录，包含当前用户 joined 状态，游标分页 |
| POST /api/departments | 管理员创建部门，`{"name":"研发部"}` |
| PUT /api/departments/{id}/membership | 当前用户加入，幂等 |
| DELETE /api/departments/{id}/membership | 当前用户退出，幂等 |
| GET /api/knowledge-bases/{id}/sharing | 获取开放范围 |
| PUT /api/knowledge-bases/{id}/sharing | 系统管理员或所有者修改开放范围 |

开放范围请求示例：`{"visibility":"DEPARTMENTS","departmentIds":[1,2]}`。`PRIVATE`、`ALL` 必须传空数组；`DEPARTMENTS` 必须选择 1–100 个有效部门。创建知识库可同时传 `name` 和上述字段，事务保证失败时不留下半成品；旧客户端只传名称仍创建私有知识库。

升级时由 Flyway 自动运行 `V21__department_access.sql`，保留已有知识库成员和角色，已有知识库默认 PRIVATE。前端需重新构建，后端需重启。数据库迁移增加部门表、成员关系、共享关系以及统一的 `knowledge_access` 权限视图。

验证：`./mvnw.cmd -Dtest=KnowledgeBaseTests,DocumentTests,VectorTests,Bm25Tests,AuthenticationTests test`；前端目录运行 `npm test`、`npm run build`。后端集成测试需要 Docker，使用隔离的测试容器。
