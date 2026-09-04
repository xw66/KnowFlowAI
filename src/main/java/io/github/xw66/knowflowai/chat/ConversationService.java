package io.github.xw66.knowflowai.chat;

import java.time.LocalDateTime;
import java.util.*;
import io.github.xw66.knowflowai.knowledge.KnowledgeBaseService;
import io.github.xw66.knowflowai.retrieval.SearchService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationService {
    private final JdbcClient jdbc;
    private final KnowledgeBaseService bases;
    public ConversationService(JdbcClient jdbc,KnowledgeBaseService bases) { this.jdbc=jdbc; this.bases=bases; }

    @Transactional
    public Turn begin(long user,long base,Long conversationId,String question) {
        jdbc.sql("SELECT id FROM knowledge_base WHERE id=:id FOR SHARE").param("id",base).query(Long.class).optional();
        bases.get(user,base);
        long conversation;
        if(conversationId==null) {
            var key=new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO conversation(user_id,knowledge_base_id) VALUES(:user,:base)").param("user",user).param("base",base).update(key);
            conversation=Objects.requireNonNull(key.getKey()).longValue();
        } else {
            conversation=conversationId;
            long found=jdbc.sql("SELECT knowledge_base_id FROM conversation WHERE id=:id AND user_id=:user FOR UPDATE")
                    .param("id",conversation).param("user",user).query(Long.class).optional().orElseThrow(ConversationService::missing);
            if(found!=base) throw missing();
        }
        recover(conversation);
        if(!jdbc.sql("SELECT id FROM chat_message WHERE conversation_id=:id AND status='RUNNING' FOR UPDATE")
                .param("id",conversation).query(Long.class).list().isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"该会话正在生成回答");
        jdbc.sql("INSERT INTO chat_message(conversation_id,role,status,content,finished_at) VALUES(:id,'USER','COMPLETED',:question,CURRENT_TIMESTAMP(6))")
                .param("id",conversation).param("question",question).update();
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO chat_message(conversation_id,role,status,content) VALUES(:id,'ASSISTANT','RUNNING','')").param("id",conversation).update(key);
        return new Turn(conversation,Objects.requireNonNull(key.getKey()).longValue());
    }

    @Transactional
    public void evidence(Turn turn,Map<String,SearchService.Hit> evidence) {
        for(var entry:evidence.entrySet()) {
            var hit=entry.getValue();
            jdbc.sql("""
                    INSERT INTO message_citation(message_id,citation_id,chunk_id,document_id,document_name,page_number,paragraph_number,source_content)
                    VALUES(:message,:label,:chunk,:document,:name,:page,:paragraph,:content)
                    """).param("message",turn.messageId()).param("label",entry.getKey()).param("chunk",hit.chunkId())
                    .param("document",hit.documentId()).param("name",hit.documentName()).param("page",hit.pageNumber())
                    .param("paragraph",hit.paragraphNumber()).param("content",hit.content()).update();
        }
    }

    @Transactional
    public void complete(Turn turn,String content,List<AnswerService.Citation> citations,String model,AnswerService.Usage usage) {
        var owner=jdbc.sql("SELECT user_id,knowledge_base_id FROM conversation WHERE id=:id FOR SHARE").param("id",turn.conversationId()).query().singleRow();
        long base=((Number)owner.get("knowledge_base_id")).longValue();
        jdbc.sql("SELECT id FROM knowledge_base WHERE id=:id FOR SHARE").param("id",base).query(Long.class).single();
        bases.get(((Number)owner.get("user_id")).longValue(),base);
        if(invalidEvidence(turn.messageId(),base)) throw new ResponseStatusException(HttpStatus.CONFLICT,"知识内容已变化，请重新提问");
        int changed=finish(turn,"COMPLETED",content,model,usage,null);
        if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"消息已结束，不能覆盖");
        for(var citation:citations) {
            int matched=jdbc.sql("""
                    UPDATE message_citation SET cited=TRUE,quoted_text=:quote
                    WHERE message_id=:message AND citation_id=:label AND chunk_id=:chunk
                      AND LOCATE(BINARY :quote,BINARY source_content)>0
                    """).param("quote",citation.quote()).param("message",turn.messageId()).param("label",citation.id())
                    .param("chunk",citation.chunkId()).update();
            if(matched!=1) throw new IllegalStateException("引用与已保存证据不一致");
        }
    }

    public void terminate(Turn turn,String status,String content,String model,AnswerService.Usage usage,String code) {
        if(!Set.of("FAILED","CANCELLED").contains(status)) throw new IllegalArgumentException("消息终态无效");
        finish(turn,status,content,model,usage,code);
    }
    private int finish(Turn turn,String status,String content,String model,AnswerService.Usage usage,String code) {
        return jdbc.sql("""
                UPDATE chat_message SET status=:status,content=:content,model=:model,input_tokens=:input,
                  output_tokens=:output,total_tokens=:total,error_code=:code,finished_at=CURRENT_TIMESTAMP(6)
                WHERE id=:id AND conversation_id=:conversation AND status='RUNNING'
                """).param("status",status).param("content",content).param("model",model)
                .param("input",usage==null?null:usage.inputTokens()).param("output",usage==null?null:usage.outputTokens())
                .param("total",usage==null?null:usage.totalTokens()).param("code",code).param("id",turn.messageId())
                .param("conversation",turn.conversationId()).update();
    }
    public void terminateSafely(Turn turn,String status,String content,String model,AnswerService.Usage usage,String code) {
        try { terminate(turn,status,content,model,usage,code); }
        catch(RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).atError().addKeyValue("messageId",turn.messageId())
                    .addKeyValue("exceptionType",error.getClass().getSimpleName()).log("消息终态保存失败，等待恢复检查");
        }
    }

    @Transactional
    public RewriteContext rewriteContext(long user,long base,Turn turn) {
        requireTurn(user,base,turn);
        var previous=jdbc.sql("SELECT id,content,status FROM chat_message WHERE conversation_id=:conversation AND id<:id AND role='ASSISTANT' ORDER BY id DESC LIMIT 1")
                .param("conversation",turn.conversationId()).param("id",turn.messageId()).query().listOfRows();
        if(previous.isEmpty()) return null;
        var row=previous.getFirst();
        long id=((Number)row.get("id")).longValue();
        if(!row.get("status").equals("COMPLETED") || invalidEvidence(id,base)) return null;
        String question=jdbc.sql("SELECT content FROM chat_message WHERE conversation_id=:conversation AND role='USER' AND id<:id ORDER BY id DESC LIMIT 1")
                .param("conversation",turn.conversationId()).param("id",id).query(String.class).single();
        String answer=(String)row.get("content");
        if(answer.length()>2000) answer=answer.substring(0,Character.isHighSurrogate(answer.charAt(1999))?1999:2000);
        return new RewriteContext(id,question,answer);
    }

    @Transactional
    public void recordRewrite(long user,long base,Turn turn,String question,QueryRewriteService.Result result) {
        requireTurn(user,base,turn);
        if(result.sourceMessageId()!=null) {
            boolean same=jdbc.sql("SELECT EXISTS(SELECT 1 FROM chat_message WHERE id=:source AND conversation_id=:conversation AND id<:id AND status='COMPLETED' AND role='ASSISTANT')")
                    .param("source",result.sourceMessageId()).param("conversation",turn.conversationId()).param("id",turn.messageId()).query(Boolean.class).single();
            if(!same || invalidEvidence(result.sourceMessageId(),base)) throw new ResponseStatusException(HttpStatus.CONFLICT,"改写依赖的历史内容已变化");
        }
        var usage=result.usage();
        int changed=jdbc.sql("""
                UPDATE chat_message SET original_question=:question,retrieval_query=:query,rewrite_status=:status,
                  rewrite_source_message_id=:source,rewrite_model=:model,rewrite_input_tokens=:input,rewrite_output_tokens=:output,rewrite_total_tokens=:total
                WHERE id=:id AND conversation_id=:conversation AND status='RUNNING'
                """).param("question",question).param("query",result.query()).param("status",result.status().name())
                .param("source",result.sourceMessageId()).param("model",result.model()).param("input",usage==null?null:usage.inputTokens())
                .param("output",usage==null?null:usage.outputTokens()).param("total",usage==null?null:usage.totalTokens())
                .param("id",turn.messageId()).param("conversation",turn.conversationId()).update();
        if(changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"消息已结束，不能继续检索");
    }

    @Transactional
    public void assertSourcesCurrent(long user,long base,Turn turn) {
        requireTurn(user,base,turn);
        if(invalidEvidence(turn.messageId(),base)) throw new ResponseStatusException(HttpStatus.CONFLICT,"历史证据已变化，请重新提问");
    }
    private void requireTurn(long user,long base,Turn turn) {
        jdbc.sql("SELECT id FROM knowledge_base WHERE id=:id FOR SHARE").param("id",base).query(Long.class).optional();
        bases.get(user,base);
        if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM conversation c JOIN chat_message m ON m.conversation_id=c.id WHERE c.id=:conversation AND c.user_id=:user AND c.knowledge_base_id=:base AND m.id=:id)")
                .param("conversation",turn.conversationId()).param("user",user).param("base",base).param("id",turn.messageId()).query(Boolean.class).single()) throw missing();
    }

    public List<Conversation> list(long user,long after,int limit) {
        return jdbc.sql("""
                SELECT c.id,c.knowledge_base_id,c.created_at FROM conversation c
                JOIN knowledge_base b ON b.id=c.knowledge_base_id
                JOIN knowledge_member m ON m.knowledge_base_id=b.id AND m.user_id=c.user_id
                JOIN app_user u ON u.id=c.user_id
                WHERE c.user_id=:user AND b.status='ACTIVE' AND u.status='ACTIVE' AND c.id>:after ORDER BY c.id LIMIT :limit
                """).param("user",user).param("after",after).param("limit",limit).query(Conversation.class).list();
    }

    @Transactional
    public List<Message> messages(long user,long conversation,long after,int limit) {
        long base=jdbc.sql("SELECT knowledge_base_id FROM conversation WHERE id=:id AND user_id=:user FOR SHARE")
                .param("id",conversation).param("user",user).query(Long.class).optional().orElseThrow(ConversationService::missing);
        jdbc.sql("SELECT id FROM knowledge_base WHERE id=:id FOR SHARE").param("id",base).query(Long.class).single();
        bases.get(user,base);
        recover(conversation);
        var rows=jdbc.sql("SELECT id,role,status,content,model,input_tokens,output_tokens,total_tokens,error_code,created_at,original_question,retrieval_query,rewrite_status FROM chat_message WHERE conversation_id=:id AND id>:after ORDER BY id LIMIT :limit")
                .param("id",conversation).param("after",after).param("limit",limit).query(Row.class).list();
        var result=new ArrayList<Message>();
        for(var row:rows) {
            // 任意输入来源失效时隐藏整条助手消息，不能只去掉引用而保留答案。
            boolean redacted=row.role().equals("ASSISTANT") && invalidEvidence(row.id(),base);
            var citations=redacted?List.<AnswerService.Citation>of():jdbc.sql("""
                    SELECT citation_id AS id,chunk_id,document_id,document_name,page_number,paragraph_number,quoted_text AS quote
                    FROM message_citation WHERE message_id=:id AND cited=TRUE ORDER BY citation_id
                    """).param("id",row.id()).query(AnswerService.Citation.class).list();
            var usage=row.totalTokens()==null && row.inputTokens()==null && row.outputTokens()==null?null:
                    new AnswerService.Usage(row.inputTokens(),row.outputTokens(),row.totalTokens());
            result.add(new Message(row.id(),row.role(),row.status(),redacted?null:row.content(),redacted,citations,row.model(),usage,row.errorCode(),row.createdAt(),row.originalQuestion(),redacted?null:row.retrievalQuery(),row.rewriteStatus()));
        }
        return result;
    }
    private boolean invalidEvidence(long message,long base) {
        // ponytail: 长依赖链受 MySQL 递归上限约束并拒绝读取；确有长会话需求时再扁平化来源依赖。
        return jdbc.sql("""
                WITH RECURSIVE inputs AS (
                  SELECT id,rewrite_source_message_id,status FROM chat_message WHERE id=:id
                  UNION ALL
                  SELECT m.id,m.rewrite_source_message_id,m.status FROM chat_message m JOIN inputs i ON m.id=i.rewrite_source_message_id AND m.id<i.id)
                SELECT EXISTS(SELECT 1 FROM inputs i JOIN message_citation s ON s.message_id=i.id WHERE NOT EXISTS(
                  SELECT 1 FROM document_chunk c JOIN document d ON d.id=c.document_id
                  WHERE c.id=s.chunk_id AND d.id=s.document_id AND d.knowledge_base_id=:base AND d.status='READY'
                    AND c.index_version=d.active_index_version AND BINARY c.content=BINARY s.source_content))
                  OR EXISTS(SELECT 1 FROM inputs WHERE id<>:id AND status<>'COMPLETED')
                """).param("id",message).param("base",base).query(Boolean.class).single();
    }
    private void recover(long conversation) {
        jdbc.sql("""
                UPDATE chat_message SET status='FAILED',error_code='PROCESS_INTERRUPTED',finished_at=CURRENT_TIMESTAMP(6)
                WHERE conversation_id=:id AND status='RUNNING' AND created_at<TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP(6))
                """).param("id",conversation).update();
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在或无访问权限"); }
    public record Turn(long conversationId,long messageId) {}
    public record Conversation(long id,long knowledgeBaseId,LocalDateTime createdAt) {}
    public record RewriteContext(long messageId,String question,String answer) {}
    private record Row(long id,String role,String status,String content,String model,Integer inputTokens,Integer outputTokens,Integer totalTokens,String errorCode,LocalDateTime createdAt,String originalQuestion,String retrievalQuery,String rewriteStatus) {}
    public record Message(long id,String role,String status,String content,boolean redacted,List<AnswerService.Citation> citations,String model,AnswerService.Usage usage,String errorCode,LocalDateTime createdAt,String originalQuestion,String retrievalQuery,String rewriteStatus) {}
}
