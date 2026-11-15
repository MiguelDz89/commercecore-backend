# Propuesta para una futura V4 de integridad RBAC

Estado: propuesta para revisión; **no es una migración y no debe ejecutarse**.
No se han modificado V1–V3 ni se han cambiado datos existentes.
La implementación posterior y sus límites se describen en
`v4-integridad-rbac.md`; este documento conserva el análisis previo.

La resolución de autorización inspecciona todos los roles de la membresía
activa en la empresa solicitada, en una sola sentencia y sin caché. Si alguno
es inconsistente, produce `AuthorizationIntegrityException` antes de conceder
cualquier permiso, incluso si otro rol válido lo concede. Una cuenta o
membresía inexistente o inactiva sigue produciendo denegación ordinaria. Los
fallos JDBC producen `AuthorizationEvaluationException`. Los roles de otras
empresas no participan. La delegación consulta la misma autorización, por lo que
no puede utilizar permisos de un rol inconsistente. La consulta actual se repite
por cada permiso evaluado durante la delegación; no hace una consulta por rol.

## Diagnóstico previo, solo lectura

La siguiente consulta identifica roles protegidos con conjunto incorrecto,
variantes del nombre reservado y roles ordinarios con permisos prohibidos. No
modifica datos. Debe revisarse con el arquitecto y el propietario de los datos
antes de cualquier migración; no implica reparación automática.

```sql
SELECT r.tenant_id, r.role_id, r.code,
       array_agg(rp.permission_code ORDER BY rp.permission_code)
         FILTER (WHERE rp.permission_code IS NOT NULL) AS permissions
FROM app_role r
LEFT JOIN role_permission rp ON rp.tenant_id = r.tenant_id AND rp.role_id = r.role_id
GROUP BY r.tenant_id, r.role_id, r.code
HAVING r.code ~ '(^[[:space:]]|[[:space:]]$)'
    OR (upper(btrim(r.code)) = 'TENANT_ADMIN' AND
        (r.code <> 'TENANT_ADMIN' OR count(rp.permission_code) <> 3 OR
         count(rp.permission_code) FILTER (WHERE rp.permission_code IN
           ('TENANT_READ','TENANT_MANAGE','USER_MANAGE')) <> 3))
    OR (upper(btrim(r.code)) <> 'TENANT_ADMIN' AND
        count(rp.permission_code) FILTER (WHERE rp.permission_code IN
          ('TENANT_MANAGE','USER_MANAGE')) > 0);
```

## Dirección propuesta para SQL

Una restricción declarativa de fila puede reservar la grafía del código, pero
no comprobar un conjunto de filas de otra tabla. Por ejemplo, pendiente de
aprobación y validación con datos reales:

```sql
ALTER TABLE app_role ADD CONSTRAINT ck_role_reserved_code
CHECK (code !~ '(^[[:space:]]|[[:space:]]$)' AND
       (upper(code) <> 'TENANT_ADMIN' OR code = 'TENANT_ADMIN'));
```

Para el conjunto de permisos se propone una función de validación y constraint
triggers `AFTER ... DEFERRABLE INITIALLY DEFERRED` sobre `app_role` y
`role_permission`. La función consultaría el estado final de cada rol afectado:
para `TENANT_ADMIN`, exactamente los tres permisos aprobados; para cualquier rol
ordinario, ninguno de los dos permisos administrativos. Las inserciones de rol
y de sus permisos en onboarding podrían entonces ocurrir en una sola transacción.
Se debe cubrir `INSERT`, `UPDATE` y `DELETE`, incluidas ambas claves OLD y NEW
si cambia la pertenencia a rol o empresa. Un trigger inmediato adicional debe
impedir renombrar o borrar el rol protegido por la aplicación. La política de
eliminación administrativa excepcional aún requiere aprobación.

```sql
-- Esquema ilustrativo: las funciones y su control de concurrencia se definirán
-- y probarán antes de convertir esta propuesta en migración.
CREATE CONSTRAINT TRIGGER ct_role_permissions_integrity
AFTER INSERT OR UPDATE OR DELETE ON role_permission
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION validate_affected_role_permissions();

CREATE CONSTRAINT TRIGGER ct_role_integrity
AFTER INSERT OR UPDATE ON app_role
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION validate_affected_role_permissions();
```

Una comprobación diferida por sí sola no resuelve carreras entre transacciones.
Antes de cualquier escritura, triggers inmediatos y operaciones de aplicación
deberán adquirir los mismos bloqueos por empresa en orden determinista,
incluyendo OLD y NEW en cambios entre empresas; la validación se ejecutará con
semántica de aislamiento demostrada mediante pruebas de dos conexiones. Si una
operación mueve filas entre empresas, adquirir bloqueos progresivamente desde
triggers de fila puede causar deadlocks: debe definirse una API de escritura que
obtenga el conjunto completo por adelantado y revierta/reintente toda la
transacción ante timeout o deadlock. Es necesario verificar la visibilidad de
snapshots al esperar bloqueos y contemplar `SERIALIZABLE` con reintentos o un
protocolo equivalente probado; no basta asumir que el trigger ve el último
commit bajo cualquier aislamiento.

El usuario de migraciones, propietario de funciones y esquema, debe ser distinto
del usuario runtime; este último no debe poder deshabilitar triggers, alterar tablas,
actuar como propietario ni cambiar la función. Deben revisarse privilegios de
`SECURITY DEFINER`, `search_path`, RLS y operaciones de mantenimiento. Incluso
con triggers, un propietario o superusuario puede omitirlos: la separación de
privilegios y controles operacionales son parte del diseño. Antes de desplegar,
resolver los resultados del diagnóstico explícitamente, probar onboarding,
renombres, borrados, cascadas, cambios concurrentes, rollback y cargas masivas.
No se requiere que toda empresa tenga ya el rol protegido: su creación inicial
pertenece al onboarding futuro.

## Restricciones y decisiones pendientes

La política existente impide modificar un rol ordinario asignado a cualquier
membresía con `TENANT_ADMIN`, incluso si esa membresía no está activa. También
impide revocar ordinariamente un rol con permisos administrativos prohibidos o
fuera del alcance del actor. Esto puede bloquear la reparación de asignaciones
heredadas y la administración de usuarios protegidos; no hay procedimiento de
reparación aprobado y ninguna de esas reglas se cambia en este incremento.

`TENANT_ADMIN` no recibe automáticamente permisos comerciales. Dado que solo se
pueden delegar permisos que ya posee el actor, el primer conjunto comercial
requiere un futuro proceso de onboarding o administración de plataforma
explícitamente autorizado, auditado y transaccional. Su contrato, alcance y
procedimiento de recuperación aún requieren aprobación; esta tarea no los
implementa ni crea concesiones automáticas.
