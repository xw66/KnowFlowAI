CREATE OR REPLACE VIEW knowledge_access AS
SELECT knowledge_base_id, user_id,
       CASE MAX(privilege_level) WHEN 4 THEN 'ADMIN' WHEN 3 THEN 'OWNER' WHEN 2 THEN 'EDITOR' ELSE 'VIEWER' END AS role
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
    UNION ALL
    SELECT kb.id, u.id, 4 FROM knowledge_base kb JOIN app_user u ON u.system_role = 'ADMIN' AND u.status = 'ACTIVE'
    WHERE kb.status = 'ACTIVE'
) grants GROUP BY knowledge_base_id, user_id;
