# SSE 问答增量

新增 chat/AnswerStreamService，复用 AnswerService 的检索准备与证据复核，不增加表和依赖。POST /api/knowledge-bases/{id}/answers/stream 使用相同请求参数及 Bearer JWT。

事件依次为 metadata、delta、citation、usage、done；错误路径发送 error 后关闭，没有成功 done。delta 是实时生成的未完成正文，客户端只能在 done 后将回答标为完成。流式提示要求纯正文及 [C数字]，引用原文和位置由服务端生成，最终检查编号；这不是语义正确性的证明。无引用或无证据的正常拒答以 INSUFFICIENT_EVIDENCE 结束，done 的 answer 是最终展示文本，客户端必须替换临时正文。

每次发送正文前复核全部输入证据，结束时再校验。已发送的文本无法撤回；撤权或文档变化后发送 error（discard=true），客户端应清除临时文本。模型错误保留部分输出但明确未完成。客户端断开通过周期 SSE 注释写入检测，并取消 Spring AI 订阅。设置总期限，所有结束路径释放订阅。使用已有 Reactor 与 Spring MVC SseEmitter，真实 HTTP 测试验证事件顺序、截断、错误及取消；模型测试使用本地替身。

| 事件 | 关键数据 | 客户端处理 |
|---|---|---|
| metadata | status=GENERATING、provisional=true、rerankStatus、rewriteStatus、conversationId、messageId | 创建未完成消息并关联持久化记录 |
| delta | text、provisional=true | 追加临时正文，不当作完整答案 |
| citation | id、chunkId、documentId、documentName、pageNumber、paragraphNumber、quote | 保存服务端来源；quote 为该分块原文 |
| usage | model、usage（可能为 null） | 展示服务实际返回的 Token，未知不计为 0 |
| done | status=ANSWERED 或 INSUFFICIENT_EVIDENCE、answer、provisional=false | 以 answer 替换临时正文，标记完整 |
| error | code、status=FAILED、partial、discard | 标记失败；discard=true 清空临时内容；没有后续成功 done |

鉴权、参数或检索失败发生在 SSE 建立前，仍返回普通 HTTP 错误。建立后发生异常则通过 error 事件表达，HTTP 200 本身不表示回答完成。流断开而没有 done/error 时，客户端应标记取消或连接失败。默认 `CHAT_STREAM_DEADLINE=PT30S` 从检索完成、建立流时计时，注释心跳每秒一次；没有自动重连或失败续写；默认不重试，显式启用后仅正文输出前允许瞬态重试或备用模型切换。

实现依据：[Spring MVC 异步请求与断线检测](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)、[Spring AI OpenAI 流式调用](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)。不增加 WebFlux 服务端，也不将完整同步答案切片冒充模型流。
