# API administrativa inicial

Ambos recursos requieren un Bearer access token OIDC válido. El actor y sus
privilegios se obtienen del contexto de seguridad; nunca se aceptan en el cuerpo.

- `POST /api/v1/accounts/me/provisioning` no recibe datos y devuelve `200` con
  `accountId`. Es idempotente y no reactiva cuentas deshabilitadas.
- `POST /api/v1/platform/tenants` requiere `PLATFORM_SUPER_ADMIN` y devuelve
  `201` con `tenantId`, `slug` e `initialMembershipId` tras confirmar el commit.
  El cuerpo contiene `slug`, `legalName`, `displayName`, `currencyCode`,
  `timeZone`, `initialAdministratorAccountId` y `commercialProfiles`. Los
  perfiles pertenecen exclusivamente al catálogo `CommercialProfile`.

Los errores de aplicación usan `application/problem+json`, con un identificador
aleatorio de correlación y sin SQL ni detalles internos. La autenticación Bearer
la gestiona Quarkus y conserva su challenge `WWW-Authenticate`. Un conflicto de
slug devuelve 409; las fallas de infraestructura solo se clasifican como 503
cuando son identificables como conexión temporal. Otras fallas internas son 500.

OpenAPI y Swagger UI están disponibles para desarrollo y pruebas. OpenAPI se
desactiva en producción y Swagger UI no se incorpora por defecto. Antes de
cambiar esta política se requiere una decisión explícita de publicación.

El onboarding rechaza transacciones externas y ejecuta el alta completa dentro
de su propia transacción. Esta comprobación protege caminos de aplicación; SQL
externo y código interno privilegiado quedan fuera de esa garantía.

## Membresías empresariales

Las siguientes rutas requieren Bearer OIDC y mantienen las autorizaciones en
`MembershipAdministration`. El identificador de empresa no demuestra acceso.

- `GET /api/v1/tenants/{tenantId}/memberships` devuelve `items`, `offset` y
  `limit`; el desplazamiento predeterminado es 0 y el límite es 20 (máximo 100).
- `POST /api/v1/tenants/{tenantId}/memberships` acepta únicamente `accountId`
  y crea una invitación `INVITED` sin roles.
- `POST /api/v1/tenants/{tenantId}/memberships/{membershipId}/acceptance`
  acepta la invitación de la identidad autenticada.
- `POST /api/v1/tenants/{tenantId}/memberships/{membershipId}/suspension`
  y `/reactivation` modifican membresías ordinarias.

Salvo la aceptación propia, las operaciones requieren `USER_MANAGE` en la
empresa activa. Las denegaciones que el caso de uso no distingue de un recurso
inexistente devuelven 404; conflictos de estado, cuenta no disponible o
invitación duplicada devuelven 409. No se incluye un total inventado.
La auditoría y las modificaciones comparten la transacción y el bloqueo existente.
Los privilegios globales no implican permisos empresariales.

El recorrido empaquetado usa Keycloak y PostgreSQL temporales, tokens firmados y
las migraciones V1–V4. La prueba pausa solo su PostgreSQL, mantiene Keycloak
accesible y verifica 503, rollback y recuperación. Se mantiene registrada la
incidencia de Failsafe: el proceso empaquetado puede mostrar código 1 al cerrar
pese a que la verificación Maven y sus pruebas hayan terminado correctamente.

## Roles y permisos empresariales

Las rutas empresariales requieren `USER_MANAGE` y una empresa activa:

- `GET /api/v1/tenants/{tenantId}/roles` y `GET /api/v1/tenants/{tenantId}/memberships/{membershipId}/roles` devuelven páginas con `items`, `offset` y `limit`. Los valores predeterminados son 0 y 20; el límite máximo es 100.
- `GET /api/v1/tenants/{tenantId}/roles/{roleId}/permissions` devuelve códigos de permisos.
- `PUT` y `DELETE /api/v1/tenants/{tenantId}/memberships/{membershipId}/roles/{roleId}` asignan y revocan roles ordinarios. Una asignación duplicada o revocación inexistente devuelve 409.
- `PUT /api/v1/tenants/{tenantId}/roles/{roleId}/permissions` recibe exclusivamente un arreglo JSON de códigos y sustituye los permisos sujetos a las reglas de delegación.

`PUT` y `DELETE /api/v1/platform/tenants/{tenantId}/administrators/{membershipId}`
requieren `PLATFORM_SUPER_ADMIN` y designan o retiran `TENANT_ADMIN`. Las
operaciones exitosas de modificación devuelven 204. El último administrador
activo sigue protegido mediante el bloqueo y la comprobación transaccional.
No se conceden permisos empresariales implícitos a plataforma.

No es posible delegar `USER_MANAGE` o `TENANT_MANAGE` en roles ordinarios.
La política también impide modificar roles propios, roles protegidos y roles
ordinarios asignados a administradores protegidos; la revocación de roles
ordinarios requiere que el actor posea los permisos delegados de ese rol.
Las denegaciones empresariales indistinguibles de un recurso inexistente son
404. La auditoría y la modificación comparten transacción.

## Demostración con Swagger UI o cliente REST

Usar únicamente un entorno de desarrollo aislado ya configurado, con PostgreSQL
y un proveedor OIDC accesibles. Swagger UI está disponible en `/q/swagger-ui/`
en desarrollo. No guardar access tokens ni contraseñas en el repositorio.

1. Autenticar las identidades de plataforma, administradores y usuario. Con
   cada identidad llamar `POST /api/v1/accounts/me/provisioning` y guardar sus
   `accountId` para la sesión. La cuenta del administrador inicial debe existir.
2. Con el token `PLATFORM_SUPER_ADMIN`, crear dos empresas mediante
   `POST /api/v1/platform/tenants`. Usar slugs distintos y, en la primera,
   seleccionar explícitamente `CATALOG_OPERATOR` en `commercialProfiles`.
3. Cambiar al token del administrador inicial de la primera empresa. Consultar
   `GET /api/v1/tenants/{tenantId}/roles` y copiar el identificador del rol
   `CATALOG_OPERATOR`. Ese perfil comercial inicial se crea durante onboarding;
   la API todavía no permite crear roles ni conceder perfiles posteriormente.
4. Invitar la cuenta del usuario con `POST .../memberships`. Cambiar al token
   del usuario y llamar `POST .../memberships/{membershipId}/acceptance`.
5. Volver al token del administrador, asignar el rol mediante `PUT
   .../memberships/{membershipId}/roles/{roleId}`, consultar roles y permisos y
   revocarlo con `DELETE` sobre la misma ruta. Suspender y reactivar con los
   endpoints de membresía.
6. Repetir una consulta empresarial con el identificador de la otra empresa y
   comprobar que la identidad sin membresía allí no accede. El rol global de
   plataforma tampoco concede permisos empresariales implícitos.

La demostración no cubre creación pública de roles, catálogo comercial,
suspensión de empresas ni auditoría global; siguen fuera del alcance actual.

## Categorías

`GET` y `POST /api/v1/tenants/{tenantId}/categories`, `GET` y `PATCH
/api/v1/tenants/{tenantId}/categories/{categoryId}`, y los `POST` de
`deactivation` y `reactivation` administran categorías. La lectura requiere
`PRODUCT_READ` o `CATEGORY_MANAGE`; toda escritura requiere `CATEGORY_MANAGE`.
El contrato, búsqueda, transacciones y límites están detallados en
`docs/security/categorias.md`.

## Productos simples

`POST` y `GET /api/v1/tenants/{tenantId}/products`, y `GET` y `PATCH
/api/v1/tenants/{tenantId}/products/{productId}` crean, consultan y modifican
productos simples. Requieren respectivamente `PRODUCT_CREATE`, `PRODUCT_READ`
y `PRODUCT_UPDATE`. El contrato y las restricciones están en
`docs/security/productos-simples.md`.
