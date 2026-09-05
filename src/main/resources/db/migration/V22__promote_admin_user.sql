UPDATE app_user
SET system_role = 'ADMIN'
WHERE username = 'admin' AND status = 'ACTIVE';
