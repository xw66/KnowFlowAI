CREATE TABLE department (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    CONSTRAINT uk_department_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE department_member (
    department_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (department_id, user_id),
    INDEX idx_department_member_user (user_id, department_id),
    FOREIGN KEY (department_id) REFERENCES department(id),
    FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB;

ALTER TABLE knowledge_base ADD visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PRIVATE',
    ADD CONSTRAINT ck_knowledge_visibility CHECK (visibility IN ('PRIVATE', 'ALL', 'DEPARTMENTS'));

CREATE TABLE knowledge_department (
    knowledge_base_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    PRIMARY KEY (knowledge_base_id, department_id),
    FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id),
    FOREIGN KEY (department_id) REFERENCES department(id)
) ENGINE=InnoDB;

-- Shared read access never grants editing. Explicit roles take precedence.
-- ponytail: this union expands all-user grants; use correlated predicates if large directories make it costly.
CREATE VIEW knowledge_access AS
SELECT knowledge_base_id, user_id,
       CASE MAX(privilege_level) WHEN 3 THEN 'OWNER' WHEN 2 THEN 'EDITOR' ELSE 'VIEWER' END AS role
FROM (
    SELECT knowledge_base_id, user_id, CASE role WHEN 'OWNER' THEN 3 WHEN 'EDITOR' THEN 2 ELSE 1 END AS privilege_level
    FROM knowledge_member
    UNION ALL
    SELECT kb.id, u.id, 1 FROM knowledge_base kb JOIN app_user u ON u.status = 'ACTIVE'
    WHERE kb.visibility = 'ALL' AND kb.status = 'ACTIVE'
    UNION ALL
    SELECT kb.id, dm.user_id, 1 FROM knowledge_base kb
    JOIN knowledge_department kd ON kd.knowledge_base_id = kb.id
    JOIN department_member dm ON dm.department_id = kd.department_id
    WHERE kb.visibility = 'DEPARTMENTS' AND kb.status = 'ACTIVE'
) grants GROUP BY knowledge_base_id, user_id;
