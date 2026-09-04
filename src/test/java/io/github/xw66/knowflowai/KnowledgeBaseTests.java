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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@Import(KnowFlowAiApplicationTests.DatabaseConfiguration.class)
@ActiveProfiles("test")
class KnowledgeBaseTests {

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private ObjectMapper mapper;

    private Actor owner;
    private Actor member;
    private Actor outsider;

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
    void nonMemberAndSystemAdminCannotDiscoverOrManageKnowledgeBase() throws Exception {
        long id = create(owner, "机密资料");
        var invisible = send(outsider, "GET", base(id), "");
        var absent = send(outsider, "GET", base(Long.MAX_VALUE), "");
        expect(invisible, 404);
        expect(absent, 404);
        assertThat((String) JsonPath.read(invisible.body(), "$.detail"))
                .isEqualTo(JsonPath.read(absent.body(), "$.detail"));
        jdbcClient.sql("UPDATE app_user SET system_role = 'ADMIN' WHERE id = :id").param("id", outsider.id()).update();
        expect(send(outsider, "GET", "/api/admin/users/" + owner.id(), ""), 200);
        expect(send(outsider, "GET", base(id), ""), 404);
        expect(send(outsider, "PUT", base(id), json(Map.of("name", "越权修改"))), 404);
        expect(grant(outsider, id, outsider.id(), "EDITOR"), 404);
        expect(send(outsider, "GET", base(id) + "/members", ""), 404);
        expect(send(outsider, "DELETE", base(id) + "/members/" + owner.id(), ""), 404);
        assertThat(send(outsider, "GET", "/api/knowledge-bases", "").body()).isEqualTo("[]");
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
