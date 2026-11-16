# Onboarding empresarial transaccional

El caso de uso interno requiere `PLATFORM_SUPER_ADMIN` del token OIDC validado y
una cuenta local `ACTIVE` para el actor y otra cuenta local `ACTIVE` para el
administrador inicial. El actor puede seleccionarse explícitamente a sí mismo.
Las cuentas deben existir antes; no se crean cuentas de terceros ni se hacen
llamadas al proveedor de identidad durante la transacción. La elegibilidad MVP
se basa en el vínculo OIDC aprovisionado y el estado local; el esquema no guarda
una evidencia independiente de verificación ni una aprobación externa.

El coordinador comprueba la autoridad y los nombres del catálogo cerrado,
Identity resuelve al actor y bloquea ambas cuentas en orden de UUID PostgreSQL,
y valida de nuevo sus estados y el vínculo del actor. Tenant crea el registro
`ACTIVE` y devuelve un comprobante válido solo en esa transacción. Identity
adquiere una vez `TenantAdministrationLock`, comprueba el estado y las cuentas,
crea `TENANT_ADMIN` con los tres permisos aprobados, la primera membresía
`ACTIVE`, sus asignaciones y solo los perfiles comerciales seleccionados.
La primera membresía activa es una excepción explícita de bootstrap; las
invitaciones ordinarias siguen usando `INVITED`. Se auditan el tenant, los
roles, la membresía y las asignaciones dentro de la misma transacción. V4
valida sus restricciones diferibles al confirmar; cualquier fallo revierte los
registros y la auditoría. Los comprobantes de cuenta y tenant no son válidos
fuera de la transacción en que se obtuvieron.

La unicidad de `slug` proporciona conflicto para solicitudes duplicadas; no hay
claves de idempotencia ni devolución automática de una empresa preexistente.
No se reintentan deadlocks, errores de serialización ni timeouts de forma
automática. La futura desactivación global de cuentas deberá respetar el orden
cuentas por UUID antes de bloqueo de empresa; las operaciones que afecten varias
empresas necesitarán definir el conjunto completo y su orden antes de escribir.
Los bloqueos cooperativos no sustituyen las garantías de V4 frente a SQL externo.

La selección comercial está limitada al catálogo `CommercialProfile`; no se
aceptan permisos arbitrarios ni se alteran los tres permisos de `TENANT_ADMIN`.
No hay concesión implícita cuando la selección está vacía. La concesión de
perfiles después del onboarding sigue pendiente de un caso de uso de plataforma
explícito y auditado. Los intentos rechazados antes de crear empresa requieren
una solución de auditoría global, aún no implementada.

## Integridad transaccional de la creación

El servicio de onboarding abre y completa su propia transacción JTA. La creación
registra el tenant y el actor autenticado en `TransactionSynchronizationRegistry`;
el adaptador de persistencia rechaza inserciones que no tengan ese contexto. Una
sincronización interpuesta marca rollback antes del commit mientras exista un alta
pendiente. Su callback no ejecuta consultas ni operaciones CDI de negocio.

Identity emite evidencia únicamente después de crear la membresía, asignar el rol
y escribir las auditorías de bootstrap. Audit emite evidencia después de insertar
el evento de creación. El coordinador compara ambas con el contexto de la misma
transacción, empresa y actor local antes de completar el alta. Los comprobantes
se registran por identidad de objeto en la transacción y los comprobantes de
cuentas y empresa se consumen una sola vez. El registro transaccional desaparece
al terminar la transacción; el callback limpia el estado restante. Las excepciones
de auditoría y de restricciones diferibles revierten el conjunto.

Esta garantía cubre los contratos y adaptadores de aplicación de CommerceCore.
No protege frente a SQL externo, acceso privilegiado a la infraestructura interna
o manipulación deliberada de las APIs transaccionales por código privilegiado.
Una futura V5 podría reforzar la integridad persistente; no se ha creado ni
aplicado aquí. `disallowingExisting()` rechaza transacciones externas y delimita la transacción del onboarding completo;
la auditoría participa en esa misma transacción y no inicia otra.
