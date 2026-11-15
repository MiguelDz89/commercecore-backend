# Bloqueos para administración empresarial

La administración futura de membresías y roles debe obtener todos los bloqueos
de empresa mediante `TenantAdministrationLock.acquireAll` una sola vez, dentro
de la transacción que contiene los cambios y la auditoría. La implementación
ordena y deduplica los UUID y obtiene advisory locks transaccionales de
PostgreSQL en el espacio `commercecore:identity:tenant-admin:`. Una colisión
del hash solo serializa empresas adicionales; no elimina exclusión mutua.

## Roles y delegación

Identity consulta roles y asignaciones por empresa. Las operaciones ordinarias
requieren `USER_MANAGE` y vuelven a evaluar los permisos efectivos después de
adquirir el bloqueo. Un rol ordinario solo puede asignarse o revocarse si sus
permisos están dentro del alcance del actor. Para cambiar sus permisos, tanto
el conjunto anterior como el nuevo deben estar dentro de ese alcance. No se
delegan `TENANT_MANAGE` ni `USER_MANAGE` mediante roles ordinarios. Tampoco se
modifican asignaciones propias ni roles asignados al propio actor.

Como restricción adicional conservadora, un administrador empresarial tampoco
puede cambiar un rol ordinario asignado a cualquier membresía que tenga
`TENANT_ADMIN`: ese cambio alteraría los permisos efectivos de una membresía
protegida. La asignación y revocación ordinarias también rechazan actuar sobre
esas membresías. Los datos previos incompatibles se rechazan; no se reparan
automáticamente.

La revocación ordinaria aplica también la comprobación de alcance y rechaza
roles con permisos no delegables, incluso cuando el propósito sea retirar una
asignación heredada incompatible. La corrección de esos datos requiere un
procedimiento administrativo explícito; no se realiza silenciosamente.

Solo `PLATFORM_SUPER_ADMIN`, obtenido del cliente OIDC configurado y con cuenta
local activa para auditoría, puede designar o retirar `TENANT_ADMIN`. El rol
debe existir en la empresa con exactamente `TENANT_READ`, `TENANT_MANAGE` y
`USER_MANAGE`; no se crea automáticamente. La designación exige cuenta y
membresía destinatarias activas. La autoridad global no concede permisos de
empresa implícitos. Los cambios y su auditoría se guardan en la misma
transacción; ante error se revierten juntos.

La resolución RBAC rechaza con error de integridad las membresías activas que
tengan roles inconsistentes, además de la validación al materializar el rol.
V1–V3 no impone esos conjuntos en PostgreSQL. V4 añade restricciones de base
de datos, pero todavía no se ha aplicado a las bases persistentes; ver
`v4-integridad-rbac.md` antes de cualquier aplicación posterior.

La espera máxima por cada bloqueo es de tres segundos. La configuración previa
de `lock_timeout` se restablece después de adquirirlos. Un timeout (`55P03`),
deadlock (`40P01`) u otro error se propaga como excepción y debe revertir la
transacción completa. No se reintenta automáticamente: el caso de uso podrá
reintentar la operación completa después del rollback, con límite y sin duplicar
efectos externos.

Las asignaciones y retiradas de roles empresariales utilizan este bloqueo antes
de volver a comprobar autorización, estado empresarial y membresías. Las
retiradas de `TENANT_ADMIN` cuentan cuentas y membresías activas bajo el mismo
bloqueo y rechazan retirar al último administrador activo. Si una empresa ya no
tiene administradores activos, debe definirse un procedimiento explícito de
recuperación autorizado por plataforma; este incremento no crea roles ni
repara datos automáticamente. La desactivación global de cuentas sigue fuera
de alcance y necesitará coordinar los bloqueos de todas las empresas afectadas.

Tras obtener el bloqueo, los casos de uso deben volver a comprobar la
autorización, el estado del tenant, los roles efectivos y el número de
administradores activos antes de escribir y registrar auditoría. Las pruebas de
concurrencia ejecutan retiradas mediante transacciones PostgreSQL independientes
y verifican que solo una puede retirar a uno de los dos últimos administradores.

Antes de implementar desactivación global debe acordarse el orden de bloqueo de
cuentas y de todos sus tenants, incluyendo altas concurrentes de membresía.
Los bloqueos son cooperativos: escrituras directas que no respeten el protocolo
pueden violar la regla. La auditoría global sin tenant también queda pendiente.

Una futura suspensión o cierre de una empresa debe coordinarse con las
operaciones de membresía mediante el mismo protocolo de exclusión transaccional:
ambas operaciones deben adquirir el bloqueo de la empresa antes de comprobar
su estado y conservarlo hasta el commit. Una lectura aislada del estado no
impide que otra transacción suspenda la empresa antes de la escritura. Como el
protocolo actual pertenece a Identity, su futura ubicación compartida y el
orden de adquisición deben definirse mediante contratos públicos, sin crear una
dependencia circular entre módulos.
