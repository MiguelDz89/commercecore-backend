-- PostgreSQL: install only after reviewing existing roles. Flyway runs this file
-- transactionally; table locks prevent writes between the audit and installation.
LOCK TABLE app_role, role_permission, permission IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE bad record;
BEGIN
    IF (SELECT count(*) FROM permission WHERE permission_code IN ('TENANT_READ','TENANT_MANAGE','USER_MANAGE')) <> 3 THEN
        RAISE EXCEPTION 'RBAC integrity: required permission catalog entries are missing' USING ERRCODE = '23514';
    END IF;
    SELECT r.tenant_id, r.role_id, r.code INTO bad
    FROM app_role r LEFT JOIN role_permission p ON p.tenant_id=r.tenant_id AND p.role_id=r.role_id
    GROUP BY r.tenant_id,r.role_id,r.code
    HAVING r.code ~ '(^[[:space:]]|[[:space:]]$)'
       OR (upper(btrim(r.code))='TENANT_ADMIN' AND (r.code <> 'TENANT_ADMIN'
          OR count(p.permission_code) <> 3
          OR count(p.permission_code) FILTER (WHERE p.permission_code IN ('TENANT_READ','TENANT_MANAGE','USER_MANAGE')) <> 3))
       OR (upper(btrim(r.code)) <> 'TENANT_ADMIN'
          AND count(p.permission_code) FILTER (WHERE p.permission_code IN ('TENANT_MANAGE','USER_MANAGE')) > 0)
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'RBAC integrity: incompatible role % in tenant % (code %)', bad.role_id,bad.tenant_id,bad.code
            USING ERRCODE = '23514';
    END IF;
END $$;

ALTER TABLE app_role ADD CONSTRAINT ck_role_reserved_code
CHECK (code !~ '(^[[:space:]]|[[:space:]]$)' AND (upper(code) <> 'TENANT_ADMIN' OR code='TENANT_ADMIN'));

-- Updating the parent creates a row version, rather than only locking it. This
-- serializes SQL writers of a role and forces stale REPEATABLE READ snapshots
-- to fail with 40001. Multiple-role operations may deadlock and must retry.
CREATE FUNCTION rbac_lock_permission_role() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE item record;
BEGIN
    FOR item IN
        SELECT DISTINCT tenant_id,role_id FROM (
            SELECT CASE WHEN TG_OP <> 'INSERT' THEN OLD.tenant_id END AS tenant_id,
                   CASE WHEN TG_OP <> 'INSERT' THEN OLD.role_id END AS role_id
            UNION ALL
            SELECT CASE WHEN TG_OP <> 'DELETE' THEN NEW.tenant_id END,
                   CASE WHEN TG_OP <> 'DELETE' THEN NEW.role_id END
        ) affected WHERE tenant_id IS NOT NULL ORDER BY tenant_id,role_id
    LOOP
        UPDATE public.app_role SET name=name WHERE tenant_id=item.tenant_id AND role_id=item.role_id;
    END LOOP;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;

CREATE FUNCTION rbac_guard_role() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.code='TENANT_ADMIN' THEN RAISE EXCEPTION 'RBAC integrity: protected role cannot be deleted' USING ERRCODE='23514'; END IF;
        RETURN OLD;
    END IF;
    IF OLD.code='TENANT_ADMIN' AND (NEW.code<>OLD.code OR NEW.tenant_id<>OLD.tenant_id OR NEW.role_id<>OLD.role_id OR NEW.name<>OLD.name) THEN
        RAISE EXCEPTION 'RBAC integrity: protected role cannot be renamed or moved' USING ERRCODE='23514';
    END IF;
    IF OLD.code<>'TENANT_ADMIN' AND upper(btrim(NEW.code))='TENANT_ADMIN' THEN
        RAISE EXCEPTION 'RBAC integrity: ordinary role cannot become protected' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION rbac_guard_catalog() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    IF OLD.permission_code IN ('TENANT_READ','TENANT_MANAGE','USER_MANAGE')
       AND (TG_OP='DELETE' OR NEW.permission_code<>OLD.permission_code) THEN
        RAISE EXCEPTION 'RBAC integrity: protected catalog permission cannot be deleted or renamed' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;

CREATE FUNCTION rbac_reject_truncate() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    RAISE EXCEPTION 'RBAC integrity: TRUNCATE is prohibited on %', TG_TABLE_NAME USING ERRCODE='23514';
END $$;

CREATE FUNCTION rbac_check_role(p_tenant uuid,p_role uuid) RETURNS void LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE role_code text; n bigint; approved bigint; forbidden bigint;
BEGIN
    SELECT code INTO role_code FROM app_role WHERE tenant_id=p_tenant AND role_id=p_role;
    IF NOT FOUND THEN RETURN; END IF;
    SELECT count(*),count(*) FILTER (WHERE permission_code IN ('TENANT_READ','TENANT_MANAGE','USER_MANAGE')),
           count(*) FILTER (WHERE permission_code IN ('TENANT_MANAGE','USER_MANAGE'))
      INTO n,approved,forbidden FROM role_permission WHERE tenant_id=p_tenant AND role_id=p_role;
    IF (role_code='TENANT_ADMIN' AND (n<>3 OR approved<>3)) OR (role_code<>'TENANT_ADMIN' AND forbidden>0) THEN
        RAISE EXCEPTION 'RBAC integrity: incompatible permissions for role % in tenant %',p_role,p_tenant USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION rbac_validate_affected_roles() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN PERFORM rbac_check_role(OLD.tenant_id,OLD.role_id); END IF;
    IF TG_OP <> 'DELETE' THEN PERFORM rbac_check_role(NEW.tenant_id,NEW.role_id); END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER rbac_permission_role_lock BEFORE INSERT OR UPDATE OR DELETE ON role_permission
FOR EACH ROW EXECUTE FUNCTION rbac_lock_permission_role();
-- Existing triggers retain their binding; untrusted users must not attach this
-- privileged trigger function to arbitrary tables.
REVOKE ALL ON FUNCTION rbac_lock_permission_role() FROM PUBLIC;
CREATE TRIGGER rbac_role_guard BEFORE UPDATE OR DELETE ON app_role
FOR EACH ROW EXECUTE FUNCTION rbac_guard_role();
CREATE TRIGGER rbac_catalog_guard BEFORE UPDATE OR DELETE ON permission
FOR EACH ROW EXECUTE FUNCTION rbac_guard_catalog();
CREATE TRIGGER rbac_no_truncate_role BEFORE TRUNCATE ON app_role FOR EACH STATEMENT EXECUTE FUNCTION rbac_reject_truncate();
CREATE TRIGGER rbac_no_truncate_role_permission BEFORE TRUNCATE ON role_permission FOR EACH STATEMENT EXECUTE FUNCTION rbac_reject_truncate();
CREATE TRIGGER rbac_no_truncate_permission BEFORE TRUNCATE ON permission FOR EACH STATEMENT EXECUTE FUNCTION rbac_reject_truncate();
CREATE CONSTRAINT TRIGGER rbac_role_integrity AFTER INSERT OR UPDATE ON app_role
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION rbac_validate_affected_roles();
CREATE CONSTRAINT TRIGGER rbac_permissions_integrity AFTER INSERT OR UPDATE OR DELETE ON role_permission
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION rbac_validate_affected_roles();
