package io.github.xw66.knowflowai;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.cache.enabled=false", "app.rate-limit.enabled=false", "app.idempotency.enabled=false",
        "management.health.redis.enabled=false", "app.chat.enabled=false", "app.embedding.enabled=false"})
@Import(KnowFlowAiApplicationTests.DatabaseConfiguration.class)
@ActiveProfiles("test")
class ReconcileTests {
    @TempDir static Path storageDirectory;
    @DynamicPropertySource static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.document.storage-directory", () -> storageDirectory.toString());
    }
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;
    final ObjectMapper json = new ObjectMapper();

    @Test void previewDefaultsToReadOnlyAndExecutionUsesStageSpecificAttempts() throws Exception {
        long parsing = task("PARSING",3,0,false), vector = task("INDEXING",3,0,true);
        String ids = "\"taskIds\":["+parsing+","+vector+"]";
        var preview = json.readTree(post("{"+ids+"}",token("ADMIN")).body());
        assertThat(preview.path("dryRun").asBoolean()).isTrue();
        assertThat(preview.path("changedTasks").asInt()).isZero();
        assertThat(preview.at("/tasks/0/nextStatus").asText()).isEqualTo("FAILED");
        assertThat(preview.at("/tasks/1/nextStatus").asText()).isEqualTo("RETRY_WAIT");
        assertThat(state(parsing)).isEqualTo("PROCESSING");
        assertThat(jdbc.sql("SELECT cache_version FROM document_task WHERE id=:id").param("id",parsing).query(Long.class).single()).isEqualTo(1);
        var applied=post("{"+ids+",\"dryRun\":false}",token("ADMIN"));
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(200);
        assertThat(json.readTree(applied.body()).path("changedTasks").asInt()).isEqualTo(2);
        assertThat(state(parsing)).isEqualTo("FAILED");
        assertThat(state(vector)).isEqualTo("RETRY_WAIT");
        assertThat(jdbc.sql("SELECT status FROM document WHERE id=(SELECT document_id FROM document_task WHERE id=:id)").param("id",parsing).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT active_index_version FROM document WHERE id=(SELECT document_id FROM document_task WHERE id=:id)").param("id",vector).query(Integer.class).single()).isEqualTo(1);
        assertThat(json.readTree(post("{"+ids+",\"dryRun\":false}",token("ADMIN")).body()).path("changedTasks").asInt()).isZero();
    }

    @Test void exhaustedVectorRetainsActiveDocumentAndClearsLease() throws Exception {
        long id=task("INDEXING",0,3,true);
        var response=post("{\"taskIds\":["+id+","+id+"],\"dryRun\":false}",token("ADMIN"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("changedTasks").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT error_code FROM document_task WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("VECTOR_RETRY_EXHAUSTED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_task WHERE id=:id AND lease_token IS NULL AND lease_until IS NULL AND next_attempt_at IS NULL AND finished_at IS NOT NULL AND cache_version=2").param("id",id).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM document WHERE id=(SELECT document_id FROM document_task WHERE id=:id)").param("id",id).query(String.class).single()).isEqualTo("READY");
    }

    @Test void renewedLeaseOldVersionDeletedAndUnselectedTasksAreUntouched() throws Exception {
        long renewed=task("PARSING",1,0,false), old=task("PARSING",1,0,false), deleted=task("PARSING",1,0,false), unselected=task("PARSING",1,0,false);
        String body="{\"taskIds\":["+renewed+","+old+","+deleted+",9223372036854775807]";
        post(body+"}",token("ADMIN"));
        jdbc.sql("UPDATE document_task SET lease_until=TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP(6)) WHERE id=:id").param("id",renewed).update();
        jdbc.sql("UPDATE document SET index_version=2 WHERE id=(SELECT document_id FROM document_task WHERE id=:id)").param("id",old).update();
        jdbc.sql("UPDATE document SET status='DELETED' WHERE id=(SELECT document_id FROM document_task WHERE id=:id)").param("id",deleted).update();
        var response=post(body+",\"dryRun\":false}",token("ADMIN"));
        assertThat(response.body()).contains("LEASE_NOT_EXPIRED","OLD_VERSION","DOCUMENT_DELETED","NOT_FOUND");
        assertThat(json.readTree(response.body()).path("changedTasks").asInt()).isZero();
        for(long id:List.of(renewed,old,deleted,unselected)) assertThat(state(id)).isEqualTo("PROCESSING");
    }

    @Test void validatesScopeAndRequiresAdministrator() throws Exception {
        String admin=token("ADMIN");
        for(String body:List.of("{}","{\"taskIds\":[]}","{\"taskIds\":[0]}","{\"taskIds\":[null]}","{\"taskIds\":["+"1,".repeat(100)+"1]}"))
            assertThat(post(body,admin).statusCode()).as(body).isEqualTo(400);
        assertThat(post("{\"taskIds\":[1]}",null).statusCode()).isEqualTo(401);
        assertThat(post("{\"taskIds\":[1]}",token("USER")).statusCode()).isEqualTo(403);
    }

    String state(long id) { return jdbc.sql("SELECT status FROM document_task WHERE id=:id").param("id",id).query(String.class).single(); }
    @Test void externalReportRequiresAdminAndPagesWithinRequestedKnowledgeBase() throws Exception {
        long first=task("PARSING",0,0,false), second=task("PARSING",0,0,false);
        long base=jdbc.sql("SELECT knowledge_base_id FROM document WHERE id=(SELECT document_id FROM document_task WHERE id=:id)").param("id",first).query(Long.class).single();
        long doc=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",first).query(Long.class).single();
        String admin=token("ADMIN");
        assertThat(get("?knowledgeBaseId="+base,null).statusCode()).isEqualTo(401);
        assertThat(get("?knowledgeBaseId="+base,token("USER")).statusCode()).isEqualTo(403);
        for(String query:List.of("","?knowledgeBaseId=0","?knowledgeBaseId="+base+"&limit=11","?knowledgeBaseId="+base+"&afterId=-1"))
            assertThat(get(query,admin).statusCode()).isEqualTo(400);
        var single=json.readTree(get("?knowledgeBaseId="+base+"&limit=1",admin).body());
        assertThat(single.path("documents").size()).isEqualTo(1);
        assertThat(single.at("/documents/0/documentId").asLong()).isEqualTo(doc);
        assertThat(single.path("nextAfterId").isNull()).isTrue();
        jdbc.sql("UPDATE document SET knowledge_base_id=:base WHERE id=(SELECT document_id FROM document_task WHERE id=:id)").param("base",base).param("id",second).update();
        var response=get("?knowledgeBaseId="+base+"&limit=1",admin);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var page=json.readTree(response.body());
        assertThat(page.path("nextAfterId").asLong()).isEqualTo(doc);
        var next=json.readTree(get("?knowledgeBaseId="+base+"&limit=1&afterId="+doc,admin).body());
        assertThat(next.path("documents").size()).isEqualTo(1);
        assertThat(next.at("/documents/0/documentId").asLong()).isGreaterThan(doc);
        assertThat(next.path("nextAfterId").isNull()).isTrue();
    }

    @Test void fileScanClassifiesRegisteredTemporaryAndOrphanEntriesWithCursor() throws Exception {
        long task=task("PARSING",0,0,false);
        long document=jdbc.sql("SELECT document_id FROM document_task WHERE id=:id").param("id",task).query(Long.class).single();
        String registered=jdbc.sql("SELECT storage_key FROM document WHERE id=:id").param("id",document).query(String.class).single();
        Files.writeString(storageDirectory.resolve(registered),"registered");
        Files.writeString(storageDirectory.resolve("orphan.txt"),"orphan");
        Files.writeString(storageDirectory.resolve(".upload-crashed.part"),"partial");
        String admin=token("ADMIN"), cursor="";
        var statuses=new java.util.HashSet<String>();
        for(int page=0;page<5;page++) {
            var response=get("/files?limit=1&afterKey="+java.net.URLEncoder.encode(cursor,java.nio.charset.StandardCharsets.UTF_8),admin);
            assertThat(response.statusCode()).isEqualTo(200);
            var body=json.readTree(response.body());
            assertThat(body.path("storageStatus").asText()).isEqualTo("OK");
            var files=body.path("files");
            if(files.isEmpty()) break;
            statuses.add(files.get(0).path("status").asText());
            if(body.path("nextAfterKey").isNull()) break;
            cursor=body.path("nextAfterKey").asText();
        }
        assertThat(statuses).contains("REGISTERED","ORPHAN","TEMPORARY");
        assertThat(get("/files?limit=101",admin).statusCode()).isEqualTo(400);
    }
    HttpResponse<String> get(String query,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/admin/reconcile/documents"+query)).GET();
        if(token!=null) request.header("Authorization","Bearer "+token);
        try(var client=HttpClient.newHttpClient()) { return client.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    long task(String stage,int attempts,int vectorAttempts,boolean active) {
        String name=UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("INSERT INTO app_user(username,password_hash) VALUES (:name,'unused')").param("name",name).update();
        long user=jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name",name).query(Long.class).single();
        jdbc.sql("INSERT INTO knowledge_base(name,owner_id) VALUES (:name,:user)").param("name",name).param("user",user).update();
        long base=jdbc.sql("SELECT id FROM knowledge_base WHERE owner_id=:user").param("user",user).query(Long.class).single();
        jdbc.sql("INSERT INTO document(knowledge_base_id,uploaded_by,name,storage_key,sha256,media_type,size_bytes,idempotency_key,status,active_index_version) VALUES (:base,:user,'test.txt',:name,:sha,'text/plain',1,:name,:status,:active)")
                .param("base",base).param("user",user).param("name",name).param("sha","a".repeat(64)).param("status",active?"READY":"PROCESSING").param("active",active?1:null,java.sql.Types.INTEGER).update();
        long doc=jdbc.sql("SELECT id FROM document WHERE storage_key=:name").param("name",name).query(Long.class).single();
        jdbc.sql("INSERT INTO document_task(document_id,status,stage,attempts,vector_attempts,lease_token,lease_until) VALUES (:doc,'PROCESSING',:stage,:attempts,:vector,:token,TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP(6)))")
                .param("doc",doc).param("stage",stage).param("attempts",attempts).param("vector",vectorAttempts).param("token",name).update();
        return jdbc.sql("SELECT id FROM document_task WHERE document_id=:doc").param("doc",doc).query(Long.class).single();
    }
    HttpResponse<String> post(String body,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/admin/reconcile/stale-tasks"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(token!=null) request.header("Authorization","Bearer "+token);
        try(var client=HttpClient.newHttpClient()) { return client.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    String token(String role) {
        String name=UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("INSERT INTO app_user(username,password_hash,system_role) VALUES (:name,'unused',:role)").param("name",name).param("role",role).update();
        long id=jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name",name).query(Long.class).single();
        var now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer("knowflow-ai").audience(List.of("knowflow-api")).subject(Long.toString(id)).issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
}
