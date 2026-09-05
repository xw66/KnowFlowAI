package io.github.xw66.knowflowai.knowledge;

import java.util.List;
import java.util.Objects;
import java.time.Duration;
import io.github.xw66.knowflowai.cache.RedisCache;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

@Service
public class KnowledgeBaseService {

    private static final String VISIBLE_BASES = """
            SELECT kb.id, kb.name, kb.owner_id, km.role
            FROM knowledge_base kb
            JOIN knowledge_access km ON km.knowledge_base_id = kb.id
            JOIN app_user u ON u.id = km.user_id
            WHERE km.user_id = :userId AND u.status = 'ACTIVE' AND kb.status = 'ACTIVE'
            """;

    private final JdbcClient jdbcClient;
    private final RedisCache cache;

    public KnowledgeBaseService(JdbcClient jdbcClient, RedisCache cache) {
        this.jdbcClient = jdbcClient;
        this.cache = cache;
    }

    @Transactional
    public KnowledgeBaseView create(long userId, String name) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("INSERT INTO knowledge_base (name, owner_id) VALUES (:name, :userId)")
                .param("name", name.strip()).param("userId", userId).update(keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        // 任一写入失败都回滚，不能留下没有 OWNER 成员的知识库。
        jdbcClient.sql("INSERT INTO knowledge_member (knowledge_base_id, user_id, role) VALUES (:id, :userId, 'OWNER')")
                .param("id", id).param("userId", userId).update();
        return new KnowledgeBaseView(id, name.strip(), userId, "OWNER");
    }

    @Transactional
    public KnowledgeBaseView create(long userId, String name, String visibility, List<Long> departmentIds) {
        var base = create(userId, name);
        setSharing(userId, base.id(), visibility, departmentIds);
        return base;
    }

    public List<KnowledgeBaseView> list(long userId, long afterId, int limit) {
        return jdbcClient.sql(VISIBLE_BASES + " AND kb.id > :afterId ORDER BY kb.id LIMIT :limit")
                .param("userId", userId).param("afterId", afterId).param("limit", limit)
                .query(KnowledgeBaseView.class).list();
    }

    public KnowledgeBaseView get(long userId, long id) {
        if (!cache.usable()) return getFromDatabase(userId, id);
        // 权限、所有者和缓存版本以数据库为准，Redis 不保存用户角色。
        var access = jdbcClient.sql("""
                SELECT kb.owner_id, kb.cache_version, km.role
                FROM knowledge_base kb
                JOIN knowledge_access km ON km.knowledge_base_id = kb.id
                JOIN app_user u ON u.id = km.user_id
                WHERE kb.id = :id AND km.user_id = :userId
                  AND kb.status = 'ACTIVE' AND u.status = 'ACTIVE'
                """).param("id", id).param("userId", userId).query(BaseAccess.class)
                .optional().orElseThrow(KnowledgeBaseService::notFound);
        String key = cacheKey(id, access.cacheVersion());
        String name = cache.get(key);
        if (name == null) {
            var current = jdbcClient.sql("SELECT name FROM knowledge_base WHERE id = :id AND cache_version = :version AND status = 'ACTIVE'")
                    .param("id", id).param("version", access.cacheVersion()).query(String.class).optional();
            // 并发改名后不把新名称写入旧版本，也不无限重试。
            if (current.isEmpty()) return getFromDatabase(userId, id);
            name = current.get();
            cache.put(key, name, Duration.ofMinutes(5));
        }
        return new KnowledgeBaseView(id, name, access.ownerId(), access.role());
    }

    private KnowledgeBaseView getFromDatabase(long userId, long id) {
        return jdbcClient.sql(VISIBLE_BASES + " AND kb.id = :id")
                .param("userId", userId).param("id", id).query(KnowledgeBaseView.class)
                .optional().orElseThrow(KnowledgeBaseService::notFound);
    }

    @Transactional
    public KnowledgeBaseView rename(long userId, long id, String name) {
        var base = lockForEditing(userId, id);
        long version = jdbcClient.sql("SELECT cache_version FROM knowledge_base WHERE id = :id")
                .param("id", id).query(Long.class).single();
        jdbcClient.sql("UPDATE knowledge_base SET name = :name, cache_version = cache_version + 1 WHERE id = :id")
                .param("name", name.strip()).param("id", id).update();
        cache.invalidateAfterCommit(cacheKey(id, version));
        return new KnowledgeBaseView(id, name.strip(), base.ownerId(), base.role());
    }

    @Transactional
    public void setMember(long userId, long id, long memberId, String role) {
        var base = lockForWrite(userId, id);
        requireOwner(base);
        protectOwner(base, memberId);
        var target = jdbcClient.sql("SELECT id FROM app_user WHERE id = :memberId AND status = 'ACTIVE' FOR SHARE")
                .param("memberId", memberId).query(Long.class).optional();
        if (target.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标用户不存在或不可用");
        }
        jdbcClient.sql("""
                INSERT INTO knowledge_member (knowledge_base_id, user_id, role) VALUES (:id, :memberId, :role)
                ON DUPLICATE KEY UPDATE role = :role
                """)
                .param("id", id).param("memberId", memberId).param("role", role).update();
    }

    @Transactional
    public void removeMember(long userId, long id, long memberId) {
        var base = lockForWrite(userId, id);
        requireOwner(base);
        protectOwner(base, memberId);
        jdbcClient.sql("DELETE FROM knowledge_member WHERE knowledge_base_id = :id AND user_id = :memberId")
                .param("id", id).param("memberId", memberId).update();
    }

    @Transactional
    public List<MemberView> members(long userId, long id, long afterUserId, int limit) {
        requireOwner(lockForWrite(userId, id));
        return jdbcClient.sql("""
                SELECT km.user_id, u.username, km.role, u.status
                FROM knowledge_member km JOIN app_user u ON u.id = km.user_id
                WHERE km.knowledge_base_id = :id AND km.user_id > :afterUserId
                ORDER BY km.user_id LIMIT :limit
                """)
                .param("id", id).param("afterUserId", afterUserId).param("limit", limit)
                .query(MemberView.class).list();
    }

    public Sharing sharing(long userId, long id) {
        get(userId, id);
        String visibility = jdbcClient.sql("SELECT visibility FROM knowledge_base WHERE id=:id")
                .param("id", id).query(String.class).single();
        var departments = jdbcClient.sql("SELECT department_id FROM knowledge_department WHERE knowledge_base_id=:id ORDER BY department_id")
                .param("id", id).query(Long.class).list();
        return new Sharing(visibility, departments);
    }

    @Transactional
    public Sharing setSharing(long userId, long id, String visibility, List<Long> departmentIds) {
        requireOwner(lockForWrite(userId, id));
        var ids = departmentIds.stream().distinct().sorted().toList();
        if (visibility.equals("DEPARTMENTS") == ids.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指定部门时至少选择一个部门，其他范围不能包含部门");
        if (!ids.isEmpty() && jdbcClient.sql("SELECT COUNT(*) FROM department WHERE id IN (:ids)")
                .param("ids", ids).query(Long.class).single() != ids.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选部门不存在");
        jdbcClient.sql("UPDATE knowledge_base SET visibility=:visibility WHERE id=:id")
                .param("visibility", visibility).param("id", id).update();
        jdbcClient.sql("DELETE FROM knowledge_department WHERE knowledge_base_id=:id").param("id", id).update();
        for (long departmentId : ids)
            jdbcClient.sql("INSERT INTO knowledge_department(knowledge_base_id,department_id) VALUES (:id,:department)")
                    .param("id", id).param("department", departmentId).update();
        return new Sharing(visibility, ids);
    }

    public record Sharing(String visibility, List<Long> departmentIds) {}

    private KnowledgeBaseView lockForWrite(long userId, long id) {
        // 同一知识库的写入与撤权共用行锁，取得锁后再读取当前成员权限。
        jdbcClient.sql("SELECT id FROM knowledge_base WHERE id = :id AND status = 'ACTIVE' FOR UPDATE")
                .param("id", id).query(Long.class).optional().orElseThrow(KnowledgeBaseService::notFound);
        return get(userId, id);
    }

    public KnowledgeBaseView requireEditAccess(long userId, long id) {
        return requireEditor(get(userId, id));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public KnowledgeBaseView lockForEditing(long userId, long id) {
        return requireEditor(lockForWrite(userId, id));
    }

    private static KnowledgeBaseView requireEditor(KnowledgeBaseView base) {
        if (!List.of("ADMIN", "OWNER", "EDITOR").contains(base.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要知识库编辑权限");
        }
        return base;
    }

    private static void requireOwner(KnowledgeBaseView base) {
        if (!List.of("ADMIN", "OWNER").contains(base.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有系统管理员或知识库所有者可以管理成员");
        }
    }

    private static void protectOwner(KnowledgeBaseView base, long memberId) {
        if (base.ownerId() == memberId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能修改或移除知识库所有者");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在或无权访问");
    }

    public record KnowledgeBaseView(long id, String name, long ownerId, String role) {
    }

    private record BaseAccess(long ownerId, long cacheVersion, String role) {
    }

    private static String cacheKey(long id, long version) {
        return "knowflow:kb:name:v1:" + id + ":" + version;
    }

    public record MemberView(long userId, String username, String role, String status) {
    }
}
