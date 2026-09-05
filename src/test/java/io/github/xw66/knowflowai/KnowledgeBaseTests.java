package io.github.xw66.knowflowai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import io.github.xw66.knowflowai.knowledge.KnowledgeBaseService;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "app.cache.enabled=true", "app.rate-limit.enabled=false"})
@Import({KnowFlowAiApplicationTests.DatabaseConfiguration.class, KnowledgeBaseTests.RedisConfiguration.class})
@ActiveProfiles("test")
class KnowledgeBaseTests {

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path storageDirectory;

    @org.springframework.test.context.DynamicPropertySource
    static void storageProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("app.document.storage-directory", () -> storageDirectory.toString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisConfiguration {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redisContainer() {
            return new GenericContainer<>("redis:8.2.9-alpine").withExposedPorts(6379);
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private GenericContainer<?> redisContainer;
    @Autowired
    private KnowledgeBaseService service;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Actor owner;
    private Actor member;
    private Actor outsider;

    @Autowired
    private io.github.xw66.knowflowai.document.DocumentService documents;
    @Autowired
    private io.github.xw66.knowflowai.chat.ConversationService conversations;

    @Test
    void departmentMembershipCombinesGrantsAndRevokesAllReadPaths() throws Exception {
        jdbcClient.sql("UPDATE app_user SET system_role='ADMIN' WHERE id=:id").param("id", owner.id()).update();
        long first = department("研发"), second = department("产品");
        expect(send(member, "POST", "/api/departments", json(Map.of("name", "越权创建"))), 403);
        expect(send(null, "GET", "/api/departments", ""), 401);
        long id = create(owner, "跨部门资料");
        var sharing = json(Map.of("visibility", "DEPARTMENTS", "departmentIds", List.of(first, second)));
        expect(send(owner, "PUT", base(id) + "/sharing", sharing), 200);
        expect(send(member, "GET", base(id), ""), 404);
        for (long department : List.of(first, second, first))
            expect(send(member, "PUT", "/api/departments/" + department + "/membership", ""), 204);
        assertThat(service.get(member.id(), id).role()).isEqualTo("VIEWER");
        assertThat(service.list(member.id(), 0, 100)).extracting(KnowledgeBaseService.KnowledgeBaseView::id).containsExactly(id);
        expect(send(member, "PUT", base(id) + "/sharing", sharing), 403);
        expect(send(member, "PUT", base(id), json(Map.of("name", "不能编辑"))), 403);
        var upload = documents.upload(owner.id(), id, UUID.randomUUID().toString(),
                new org.springframework.mock.web.MockMultipartFile("file", "department.txt", "text/plain", "部门资料".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var turn = conversations.begin(member.id(), id, null, "部门资料？");
        conversations.terminate(turn, "CANCELLED", "", null, null, "TEST");
        for (String path : List.of(base(id) + "/documents", base(id) + "/documents/" + upload.documentId(),
                "/api/document-tasks/" + upload.taskId(), "/api/conversations/" + turn.conversationId() + "/messages"))
            expect(send(member, "GET", path, ""), 200);
        expect(send(outsider, "GET", "/api/conversations/" + turn.conversationId() + "/messages", ""), 404);
        expect(send(member, "DELETE", "/api/departments/" + first + "/membership", ""), 204);
        expect(send(member, "GET", base(id), ""), 200);
        expect(grant(owner, id, member.id(), "EDITOR"), 204);
        assertThat(service.get(member.id(), id).role()).isEqualTo("EDITOR");
        expect(send(owner, "DELETE", base(id) + "/members/" + member.id(), ""), 204);
        assertThat(service.get(member.id(), id).role()).isEqualTo("VIEWER");
        expect(send(member, "DELETE", "/api/departments/" + second + "/membership", ""), 204);
        for (String path : List.of(base(id), base(id) + "/documents", base(id) + "/documents/" + upload.documentId(),
                "/api/document-tasks/" + upload.taskId(), "/api/conversations/" + turn.conversationId() + "/messages"))
            expect(send(member, "GET", path, ""), 404);
        expect(send(member, "POST", base(id) + "/search", json(Map.of("query", "部门资料", "topK", 5))), 404);
        assertThat(send(member, "GET", "/api/conversations", "").body()).isEqualTo("[]");
        expect(send(member, "PUT", "/api/departments/" + Long.MAX_VALUE + "/membership", ""), 404);
        expect(send(member, "GET", "/api/departments?limit=101", ""), 400);
    }

    @Test
    void sharingDefaultsPrivateValidatesAndChangesCachedAccessImmediately() throws Exception {
        long id = create(owner, "开放范围");
        assertThat(service.sharing(owner.id(), id).visibility()).isEqualTo("PRIVATE");
        for (var invalid : List.of(Map.of("visibility", "DEPARTMENTS", "departmentIds", List.of()),
                Map.of("visibility", "DEPARTMENTS", "departmentIds", List.of(Long.MAX_VALUE)),
                Map.of("visibility", "ALL", "departmentIds", List.of(1)),
                Map.of("visibility", "UNKNOWN", "departmentIds", List.of())))
            expect(send(owner, "PUT", base(id) + "/sharing", json(invalid)), 400);
        long before = jdbcClient.sql("SELECT COUNT(*) FROM knowledge_base").query(Long.class).single();
        expect(send(owner, "POST", "/api/knowledge-bases", json(Map.of("name", "回滚", "visibility", "DEPARTMENTS", "departmentIds", List.of(Long.MAX_VALUE)))), 400);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM knowledge_base").query(Long.class).single()).isEqualTo(before);
        expect(send(owner, "PUT", base(id) + "/sharing", json(Map.of("visibility", "ALL", "departmentIds", List.of()))), 200);
        try {
            assertThat(service.get(member.id(), id).role()).isEqualTo("VIEWER");
            expect(send(outsider, "GET", base(id), ""), 200);
            expect(send(null, "GET", base(id), ""), 401);
            expect(send(member, "PUT", base(id), json(Map.of("name", "只读"))), 403);
            expect(grant(owner, id, member.id(), "EDITOR"), 204);
            assertThat(service.get(member.id(), id).role()).isEqualTo("EDITOR");
            jdbcClient.sql("UPDATE app_user SET status='DISABLED' WHERE id=:id").param("id", outsider.id()).update();
            expect(send(outsider, "GET", base(id), ""), 401);
        } finally {
            service.setSharing(owner.id(), id, "PRIVATE", List.of());
        }
        expect(send(member, "GET", base(id), ""), 200);
        expect(send(owner, "DELETE", base(id) + "/members/" + member.id(), ""), 204);
        expect(send(member, "GET", base(id), ""), 404);
    }

    private long department(String name) throws Exception {
        var response = send(owner, "POST", "/api/departments", json(Map.of("name", name + UUID.randomUUID())));
        expect(response, 201);
        return ((Number) JsonPath.read(response.body(), "$.id")).longValue();
    }

    @Test
    void cachesNameWithTtlButAlwaysReadsCurrentRoleAndMembership() throws Exception {
        long id = create(owner, "缓存资料");
        expect(grant(owner, id, member.id(), "EDITOR"), 204);
        assertThat(service.get(owner.id(), id).name()).isEqualTo("缓存资料");
        String key = cacheKey(id, 1);
        assertThat(redis.opsForValue().get(key)).isEqualTo("缓存资料");
        assertThat(redis.getExpire(key)).isBetween(1L, 300L);
        assertThat(service.get(member.id(), id).role()).isEqualTo("EDITOR");
        expect(grant(owner, id, member.id(), "VIEWER"), 204);
        assertThat(service.get(member.id(), id).role()).isEqualTo("VIEWER");
        expect(send(owner, "DELETE", base(id) + "/members/" + member.id(), ""), 204);
        expect(send(member, "GET", base(id), ""), 404);
        expect(send(outsider, "GET", base(id), ""), 404);
        jdbcClient.sql("UPDATE app_user SET status = 'DISABLED' WHERE id = :id").param("id", owner.id()).update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get(owner.id(), id))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(redis.hasKey(key)).isTrue();
    }

    @Test
    void renameInvalidatesAfterCommitAndLateOldFillCannotOverrideNewVersion() throws Exception {
        long id = create(owner, "旧名称");
        service.get(owner.id(), id);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            service.rename(owner.id(), id, "新名称");
            assertThat(redis.opsForValue().get(cacheKey(id, 1))).isEqualTo("旧名称");
            assertThat(service.get(owner.id(), id).name()).isEqualTo("新名称");
            assertThat(redis.hasKey(cacheKey(id, 2))).isFalse();
        });
        assertThat(redis.hasKey(cacheKey(id, 1))).isFalse();
        // 模拟提交前的读取者在提交和失效之后才回填旧名称。
        redis.opsForValue().set(cacheKey(id, 1), "旧名称", Duration.ofMinutes(5));
        assertThat(service.get(owner.id(), id).name()).isEqualTo("新名称");
        assertThat(redis.opsForValue().get(cacheKey(id, 2))).isEqualTo("新名称");
    }

    @Test
    void rolledBackRenameNeverPublishesUncommittedName() throws Exception {
        long id = create(owner, "已提交名称");
        service.get(owner.id(), id);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.rename(owner.id(), id, "必须回滚");
            assertThat(service.get(owner.id(), id).name()).isEqualTo("必须回滚");
            assertThat(redis.hasKey(cacheKey(id, 2))).isFalse();
            status.setRollbackOnly();
        });
        assertThat(service.get(owner.id(), id).name()).isEqualTo("已提交名称");
        assertThat(redis.opsForValue().get(cacheKey(id, 1))).isEqualTo("已提交名称");
        assertThat(redis.hasKey(cacheKey(id, 2))).isFalse();
    }

    @Test
    void expiredCacheRefillsFromDatabase() throws Exception {
        long id = create(owner, "过期资料");
        service.get(owner.id(), id);
        redis.expire(cacheKey(id, 1), Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(cacheKey(id, 1))));
        assertThat(service.get(owner.id(), id).name()).isEqualTo("过期资料");
        assertThat(redis.opsForValue().get(cacheKey(id, 1))).isEqualTo("过期资料");
    }

    @Test
    void redisReadWriteAndInvalidationFailuresDoNotFailDatabaseOperations() throws Exception {
        long id = create(owner, "故障前");
        service.get(owner.id(), id);
        // 仅在隔离容器内拒绝缓存命令，同时保留 ACL 命令用于恢复。
        assertThat(redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "-get", "-set", "-del").getExitCode()).isZero();
        try {
            assertThat(service.get(owner.id(), id).name()).isEqualTo("故障前");
            expect(send(owner, "PUT", base(id), json(Map.of("name", "故障后"))), 200);
            assertThat(service.get(owner.id(), id).name()).isEqualTo("故障后");
        } finally {
            assertThat(redisContainer.execInContainer("redis-cli", "ACL", "SETUSER", "default", "+get", "+set", "+del").getExitCode()).isZero();
        }
        assertThat(redis.opsForValue().get(cacheKey(id, 1))).isEqualTo("故障前");
        assertThat(service.get(owner.id(), id).name()).isEqualTo("故障后");
        assertThat(redis.opsForValue().get(cacheKey(id, 2))).isEqualTo("故障后");
    }

    private static String cacheKey(long id, long version) {
        return "knowflow:kb:name:v1:" + id + ":" + version;
    }

    @Test
    void slowRedisTimesOutAndReturnsDatabaseName() throws Exception {
        long id = create(owner, "超时回源");
        service.get(owner.id(), id);
        assertThat(redisContainer.execInContainer("redis-cli", "CLIENT", "PAUSE", "5000", "ALL").getExitCode()).isZero();
        try {
            long started = System.nanoTime();
            assertThat(service.get(owner.id(), id).name()).isEqualTo("超时回源");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        } finally {
            assertThat(redisContainer.execInContainer("redis-cli", "CLIENT", "UNPAUSE").getExitCode()).isZero();
        }
        assertThat(service.get(owner.id(), id).name()).isEqualTo("超时回源");
    }

    @BeforeEach
    void createActors() throws Exception {
        owner = actor();
        member = actor();
        outsider = actor();
    }

    @Test
    void createPersistsOwnerAndSupportsScopedPagination() throws Exception {
        long first = create(owner, "  产品文档  ");
        long second = create(owner, "操作手册");
        create(outsider, "不可见资料");
        var detail = send(owner, "GET", base(first), "");
        expect(detail, 200);
        assertThat((String) JsonPath.read(detail.body(), "$.name")).isEqualTo("产品文档");
        assertThat((Number) JsonPath.read(detail.body(), "$.ownerId")).extracting(Number::longValue).isEqualTo(owner.id());
        assertThat((String) JsonPath.read(detail.body(), "$.role")).isEqualTo("OWNER");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM knowledge_member WHERE knowledge_base_id = :id AND user_id = :userId AND role = 'OWNER'")
                .param("id", first).param("userId", owner.id()).query(Integer.class).single()).isEqualTo(1);
        var page = send(owner, "GET", "/api/knowledge-bases?limit=1", "");
        expect(page, 200);
        List<Number> ids = JsonPath.read(page.body(), "$[*].id");
        assertThat(ids).extracting(Number::longValue).containsExactly(first);
        var next = send(owner, "GET", "/api/knowledge-bases?afterId=" + first + "&limit=1", "");
        ids = JsonPath.read(next.body(), "$[*].id");
        assertThat(ids).extracting(Number::longValue).containsExactly(second);
        expect(send(owner, "PUT", base(first), json(Map.of("name", "新名称"))), 200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EDITOR", "VIEWER"})
    void membersRespectReadWriteAndManagementBoundaries(String role) throws Exception {
        long id = create(owner, "团队资料");
        expect(grant(owner, id, member.id(), role), 204);
        expect(send(member, "GET", base(id), ""), 200);
        var accessible = send(member, "GET", "/api/knowledge-bases", "");
        List<Number> ids = JsonPath.read(accessible.body(), "$[*].id");
        assertThat(ids).extracting(Number::longValue).containsExactly(id);
        expect(send(member, "PUT", base(id), json(Map.of("name", "编辑后名称"))), role.equals("EDITOR") ? 200 : 403);
        expect(send(member, "GET", base(id) + "/members", ""), 403);
        expect(grant(member, id, outsider.id(), "EDITOR"), 403);
        expect(send(member, "DELETE", base(id) + "/members/" + owner.id(), ""), 403);
        expect(grant(owner, id, member.id(), "VIEWER"), 204);
        expect(send(member, "PUT", base(id), json(Map.of("name", "不能写入"))), 403);
        expect(send(owner, "DELETE", base(id) + "/members/" + member.id(), ""), 204);
        expect(send(member, "GET", base(id), ""), 404);
        expect(send(member, "PUT", base(id), json(Map.of("name", "已撤权"))), 404);
        assertThat(send(member, "GET", "/api/knowledge-bases", "").body()).isEqualTo("[]");
        expect(send(owner, "DELETE", base(id) + "/members/" + member.id(), ""), 204);
    }

    @Test
    void systemAdminCanDiscoverAndManageEveryKnowledgeBase() throws Exception {
        long id = create(owner, "机密资料");
        var invisible = send(outsider, "GET", base(id), "");
        var absent = send(outsider, "GET", base(Long.MAX_VALUE), "");
        expect(invisible, 404);
        expect(absent, 404);
        assertThat((String) JsonPath.read(invisible.body(), "$.detail"))
                .isEqualTo(JsonPath.read(absent.body(), "$.detail"));
        jdbcClient.sql("UPDATE app_user SET system_role = 'ADMIN' WHERE id = :id").param("id", outsider.id()).update();
        expect(send(outsider, "GET", "/api/admin/users/" + owner.id(), ""), 200);
        expect(send(outsider, "GET", base(id), ""), 200);
        assertThat((String) JsonPath.read(send(outsider, "GET", base(id), "").body(), "$.role")).isEqualTo("ADMIN");
        expect(send(outsider, "PUT", base(id), json(Map.of("name", "管理员修改"))), 200);
        expect(send(outsider, "PUT", base(id) + "/sharing", json(Map.of("visibility", "ALL", "departmentIds", List.of()))), 200);
        expect(send(outsider, "PUT", base(id) + "/sharing", json(Map.of("visibility", "PRIVATE", "departmentIds", List.of()))), 200);
        expect(grant(outsider, id, member.id(), "EDITOR"), 204);
        expect(send(outsider, "GET", base(id) + "/members", ""), 200);
        expect(send(outsider, "DELETE", base(id) + "/members/" + owner.id(), ""), 409);
        List<Number> visible = JsonPath.read(send(outsider, "GET", "/api/knowledge-bases", "").body(), "$[*].id");
        assertThat(visible).extracting(Number::longValue).contains(id);
        expect(send(null, "GET", base(id), ""), 401);
        expect(send(null, "POST", "/api/knowledge-bases", json(Map.of("name", "匿名创建"))), 401);
    }

    @Test
    void ownerCannotBeRemovedOrDemotedAndUnknownTargetsAreRejected() throws Exception {
        long id = create(owner, "所有者保护");
        expect(grant(owner, id, owner.id(), "EDITOR"), 409);
        expect(send(owner, "DELETE", base(id) + "/members/" + owner.id(), ""), 409);
        expect(grant(owner, id, member.id(), "OWNER"), 400);
        expect(grant(owner, id, Long.MAX_VALUE, "VIEWER"), 404);
        jdbcClient.sql("UPDATE app_user SET status = 'DISABLED' WHERE id = :id").param("id", member.id()).update();
        expect(grant(owner, id, member.id(), "VIEWER"), 404);
        assertThat(jdbcClient.sql("SELECT role FROM knowledge_member WHERE knowledge_base_id = :id")
                .param("id", id).query(String.class).list()).containsExactly("OWNER");
    }

    @Test
    void repeatedAndConcurrentGrantsKeepOneMembership() throws Exception {
        long id = create(owner, "并发授权");
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return grant(owner, id, member.id(), "EDITOR");
            });
            var second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return grant(owner, id, member.id(), "VIEWER");
            });
            expect(first.get(15, TimeUnit.SECONDS), 204);
            expect(second.get(15, TimeUnit.SECONDS), 204);
        }
        expect(grant(owner, id, member.id(), "VIEWER"), 204);
        expect(grant(owner, id, member.id(), "VIEWER"), 204);
        assertThat(jdbcClient.sql("SELECT role FROM knowledge_member WHERE knowledge_base_id = :id AND user_id = :userId")
                .param("id", id).param("userId", member.id()).query(String.class).list()).containsExactly("VIEWER");
        var first = send(owner, "GET", base(id) + "/members?limit=1", "");
        expect(first, 200);
        List<Number> ids = JsonPath.read(first.body(), "$[*].userId");
        assertThat(ids).extracting(Number::longValue).containsExactly(owner.id());
        var next = send(owner, "GET", base(id) + "/members?afterUserId=" + owner.id(), "");
        ids = JsonPath.read(next.body(), "$[*].userId");
        assertThat(ids).extracting(Number::longValue).containsExactly(member.id());
        assertThat(next.body()).doesNotContain("password", "bcrypt");
    }

    @Test
    void deletedKnowledgeBaseIsHiddenEvenFromOwner() throws Exception {
        long id = create(owner, "删除态");
        expect(grant(owner, id, member.id(), "EDITOR"), 204);
        jdbcClient.sql("UPDATE knowledge_base SET status = 'DELETED' WHERE id = :id").param("id", id).update();
        expect(send(owner, "GET", base(id), ""), 404);
        expect(send(member, "GET", base(id), ""), 404);
        expect(send(owner, "PUT", base(id), json(Map.of("name", "不可恢复"))), 404);
        expect(grant(owner, id, outsider.id(), "VIEWER"), 404);
        expect(send(owner, "GET", base(id) + "/members", ""), 404);
        assertThat(send(owner, "GET", "/api/knowledge-bases", "").body()).isEqualTo("[]");
    }

    @Test
    void validationRejectsInvalidNamesRolesIdsAndPagination() throws Exception {
        long id = create(owner, "参数校验");
        for (var invalid : List.of("{}", "{\"name\":null}", json(Map.of("name", "   ")), json(Map.of("name", "长".repeat(129))),
                json(Map.of("name", "伪造所有者", "ownerId", outsider.id())))) {
            expect(send(owner, "POST", "/api/knowledge-bases", invalid), 400);
        }
        for (var role : List.of("ADMIN", "owner", "viewer", "")) {
            expect(grant(owner, id, member.id(), role), 400);
        }
        for (var path : List.of("/api/knowledge-bases/0", "/api/knowledge-bases?limit=0", "/api/knowledge-bases?limit=101",
                "/api/knowledge-bases?afterId=-1", base(id) + "/members?afterUserId=-1", base(id) + "/members?limit=101")) {
            expect(send(owner, "GET", path, ""), 400);
        }
        expect(send(owner, "PUT", base(id) + "/members/0", json(Map.of("role", "EDITOR"))), 400);
        expect(send(owner, "DELETE", base(id) + "/members/-1", ""), 400);
    }

    @Test
    void failedOwnerMembershipInsertRollsBackKnowledgeBase() throws Exception {
        long before = jdbcClient.sql("SELECT COUNT(*) FROM knowledge_base").query(Long.class).single();
        // 仅在测试容器内令第二条写入失败，验证事务不会留下孤立知识库。
        jdbcClient.sql("RENAME TABLE knowledge_member TO temporarily_unavailable_members").update();
        try {
            expect(send(owner, "POST", "/api/knowledge-bases", json(Map.of("name", "必须回滚"))), 503);
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM knowledge_base").query(Long.class).single()).isEqualTo(before);
        } finally {
            jdbcClient.sql("RENAME TABLE temporarily_unavailable_members TO knowledge_member").update();
        }
    }

    private Actor actor() throws Exception {
        var credentials = json(Map.of("username", "kb_" + UUID.randomUUID().toString().replace("-", ""), "password", "test_password_123"));
        var registration = send(null, "POST", "/api/auth/register", credentials);
        expect(registration, 201);
        var login = send(null, "POST", "/api/auth/login", credentials);
        expect(login, 200);
        return new Actor(((Number) JsonPath.read(registration.body(), "$.id")).longValue(), JsonPath.read(login.body(), "$.accessToken"));
    }

    private long create(Actor actor, String name) throws Exception {
        var response = send(actor, "POST", "/api/knowledge-bases", json(Map.of("name", name)));
        expect(response, 201);
        return ((Number) JsonPath.read(response.body(), "$.id")).longValue();
    }

    private HttpResponse<String> grant(Actor actor, long id, long memberId, String role) throws Exception {
        return send(actor, "PUT", base(id) + "/members/" + memberId, json(Map.of("role", role)));
    }

    private static String base(long id) {
        return "/api/knowledge-bases/" + id;
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private static void expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("响应体：%s", response.body()).isEqualTo(status);
        if (status >= 400) {
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
        }
    }

    private HttpResponse<String> send(Actor actor, String method, String path, String body) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (actor != null) {
                request.header("Authorization", "Bearer " + actor.token());
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private record Actor(long id, String token) {
    }
}
