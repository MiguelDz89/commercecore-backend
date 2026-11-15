# V4: integridad RBAC en PostgreSQL

Estado: implementada para revisión arquitectónica y pruebas temporales. **No se
ha aplicado a `commercecore_dev` ni a otra base persistente.** El perfil de
desarrollo conserva la conexión local, pero ya no ejecuta Flyway al arrancar.

## Alcance SQL

`V4__enforce_rbac_integrity.sql` bloquea las tablas afectadas y examina los
datos antes de instalar restricciones. Si faltan permisos del catálogo o hay
roles incompatibles, aborta la migración sin reparar datos. La migración usa el
esquema `public`, coherente con la configuración actual; una instalación en
otro esquema requiere revisar el SQL y los privilegios antes de aplicarla.

Un `CHECK` reserva el código canónico. Los triggers impiden convertir un rol
ordinario a `TENANT_ADMIN`, renombrar o mover el rol protegido y eliminarlo.
Los permisos del catálogo usados por ese rol no pueden eliminarse ni cambiar
de código. Los triggers diferibles comprueban al final de la transacción que
el rol protegido contiene exactamente `TENANT_READ`, `TENANT_MANAGE` y
`USER_MANAGE`, y que los roles ordinarios no incluyen los dos últimos. Esto
permite insertar el rol y sus permisos mediante varias sentencias en la misma
transacción. No obliga a que cada empresa tenga ya un rol protegido.

Los cambios directos de `role_permission` actualizan sin cambiar el nombre
la fila propietaria del rol antes de escribir. Esta actualización obtiene una
versión y un bloqueo de fila en PostgreSQL, de modo que escritores SQL externos
se serializan sin depender del advisory lock de Java. Un snapshot obsoleto bajo
`REPEATABLE READ` puede abortar con `40001`; las colisiones entre múltiples
roles o empresas también pueden producir deadlock `40P01`. El cliente debe
reintentar la transacción completa tras rollback, con límite. Los triggers
diferibles validan los roles OLD y NEW afectados; la migración prohíbe
`TRUNCATE` de las tablas protegidas mediante triggers de sentencia.

El grafo actual no vuelve a escribir `role_permission` desde la actualización
de `app_role`: esta dispara la protección del rol y encola su validación
diferida, sin volver a invocar el trigger de bloqueo. La eliminación con
cascada de un rol ordinario está cubierta por pruebas. Esto descarta recursión
en el grafo actual, no todos los ciclos de bloqueo posibles: varias sentencias
pueden tomar roles en orden distinto, o una transacción SQL externa puede
tomar una fila antes de solicitar el advisory lock que Java obtiene primero.
PostgreSQL detecta deadlocks y revierte una transacción; los clientes deben
mantener el orden acordado y gestionar `40P01`. Cualquier trigger futuro debe
revisarse para no introducir una dependencia circular de escrituras.

La función de bloqueo es `SECURITY DEFINER`, fija `search_path`, usa el nombre
calificado de `public.app_role` y no concede `EXECUTE` a `PUBLIC`, para que un
usuario runtime no pueda adjuntarla a sus propias tablas. La propiedad de la
función y el esquema debe permanecer bajo control administrativo. El mecanismo
de Java de CODEX-010B.4 sigue denegando permisos cuando detecta datos inválidos.

## Privilegios y límites

- El propietario del esquema es una identidad administrativa controlada,
  preferiblemente sin login de aplicación. Solo ella puede alterar objetos,
  funciones, triggers o su propiedad.
- Flyway usa una identidad de migraciones distinta de la aplicación, que asume
  explícitamente el rol propietario o dispone de los permisos necesarios para
  instalar V4. La función `SECURITY DEFINER` debe pertenecer al propietario que
  pueda actualizar la fila del rol. Este acceso no debe compartirse con runtime.
- CommerceCore usa una identidad runtime sin propiedad del esquema, sin DDL,
  sin `CREATE` en `public`, sin `TRUNCATE`, sin privilegios para desactivar
  triggers ni para asumir el propietario. Sus permisos DML y de lectura deben
  limitarse a las operaciones necesarias. No se crean ni modifican usuarios
  reales como parte de V4.

Un propietario o superusuario puede alterar o eludir estos controles; no se
afirma protección frente a ellos. V4 protege la integridad de roles y permisos,
pero no impone por sí sola la regla del último administrador activo sobre
`membership_role`, ni registra auditoría de escrituras SQL externas. El
protocolo de la aplicación sigue siendo necesario. Tampoco introduce RLS ni
resuelve el onboarding, la recuperación o la desactivación global de cuentas.

## Aplicación posterior a una base de desarrollo

No ejecutar estos pasos hasta que se apruebe expresamente aplicar V4 a esa base.
Primero confirmar destino y respaldo, revisar el diagnóstico de solo lectura de
`propuesta-v4-integridad-rbac.md` y resolver cualquier inconsistencia mediante
un procedimiento autorizado; V4 no la modifica. Verificar el propietario y
los permisos de migración y runtime. Con credenciales de migración adecuadas ya
establecidas en las variables locales y tras aprobar el destino, se podrá
arrancar explícitamente con:

```powershell
.\mvnw.cmd quarkus:dev "-Dquarkus.flyway.migrate-at-start=true"
```

El comando aplica las migraciones pendientes a la URL del perfil de desarrollo
antes de arrancar la aplicación; por eso no debe usarse sin comprobar el
destino. Después, devolver las credenciales del proceso a la identidad runtime
y mantener el arranque automático desactivado. La estrategia de despliegue de
producción sigue siendo una decisión separada.
