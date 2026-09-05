package io.github.xw66.knowflowai.knowledge;

import java.util.List;
import java.util.Objects;

import io.github.xw66.knowflowai.auth.AccountService.Account;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/departments")
public class DepartmentController {
    private final JdbcClient jdbc;

    public DepartmentController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<Department> list(@AuthenticationPrincipal Account account,
            @RequestParam(defaultValue = "0") @PositiveOrZero long afterId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit) {
        return jdbc.sql("""
                SELECT d.id, d.name, (dm.user_id IS NOT NULL) AS joined
                FROM department d LEFT JOIN department_member dm ON dm.department_id=d.id AND dm.user_id=:user
                WHERE d.id>:after ORDER BY d.id LIMIT :limit
                """).param("user", account.id()).param("after", afterId).param("limit", limit)
                .query(Department.class).list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Department create(@AuthenticationPrincipal Account account, @Valid @RequestBody NameRequest request) {
        if (!account.view().role().equals("ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有管理员可以创建部门");
        var key = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO department(name) VALUES (:name)").param("name", request.name().strip()).update(key);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "部门名称已存在");
        }
        return new Department(Objects.requireNonNull(key.getKey()).longValue(), request.name().strip(), false);
    }

    @PutMapping("/{id}/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void join(@AuthenticationPrincipal Account account, @PathVariable @Positive long id) {
        requireDepartment(id);
        jdbc.sql("""
                INSERT INTO department_member(department_id,user_id) VALUES (:id,:user)
                ON DUPLICATE KEY UPDATE user_id=:user
                """).param("id", id).param("user", account.id()).update();
    }

    @DeleteMapping("/{id}/membership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal Account account, @PathVariable @Positive long id) {
        requireDepartment(id);
        jdbc.sql("DELETE FROM department_member WHERE department_id=:id AND user_id=:user")
                .param("id", id).param("user", account.id()).update();
    }

    private void requireDepartment(long id) {
        jdbc.sql("SELECT id FROM department WHERE id=:id").param("id", id).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "部门不存在"));
    }

    public record Department(long id, String name, boolean joined) {}
    public record NameRequest(@NotBlank @Size(max = 128) String name) {}
}
