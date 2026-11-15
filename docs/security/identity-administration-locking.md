# Bloqueos para administración empresarial

La administración futura de membresías y roles debe obtener todos los bloqueos
de empresa mediante `TenantAdministrationLock.acquireAll` una sola vez, dentro
de la transacción que contiene los cambios y la auditoría. La implementación
ordena y deduplica los UUID y obtiene advisory locks transaccionales de
PostgreSQL en el espacio `commercecore:identity:tenant-admin:`. Una colisión
del hash solo serializa empresas adicionales; no elimina exclusión mutua.

La espera máxima por cada bloqueo es de tres segundos. La configuración previa
de `lock_timeout` se restablece después de adquirirlos. Un timeout (`55P03`),
deadlock (`40P01`) u otro error se propaga como excepción y debe revertir la
transacción completa. No se reintenta automáticamente: el caso de uso podrá
reintentar la operación completa después del rollback, con límite y sin duplicar
efectos externos.

Tras obtener el bloqueo, los futuros casos de uso deben volver a comprobar la
autorización, el estado del tenant, los roles efectivos y el número de
administradores activos antes de escribir y registrar auditoría. La prueba de
concurrencia simula dos retiros en PostgreSQL para verificar la exclusión; no
implementa una operación administrativa de producción.

CODEX-010B.1 no implementa invitaciones, cambios de membresía, asignación de
roles ni desactivación global de cuentas. Por tanto, la regla del último
administrador todavía no está integrada en esos casos de uso inexistentes. Antes
de implementar desactivación global debe acordarse el orden de bloqueo de
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
