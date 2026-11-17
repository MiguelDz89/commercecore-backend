# Categorías multiempresa

Los seis endpoints se encuentran bajo `/api/v1/tenants/{tenantId}/categories`:
`GET` de colección y de identificador, `POST` de colección, `PATCH` de
identificador, y `POST` de `deactivation` y `reactivation`. Todos exigen OIDC;
la lectura requiere `PRODUCT_READ` o `CATEGORY_MANAGE`, y las escrituras
`CATEGORY_MANAGE`. No hay permisos implícitos de plataforma.

La colección admite `offset` (predeterminado 0), `limit` (predeterminado 20,
máximo 100) y `search` para coincidencia literal por código o nombre sin
distinguir mayúsculas. El orden es por nombre normalizado e identificador.
La creación recibe `code`, `name`, `description` y `parentCategoryId`;
`code` es inmutable. PATCH acepta solo `name`, `description` y
`parentCategoryId`: omitirlos conserva el valor y `null` elimina descripción
o padre. Las respuestas ambiguas de acceso o ausencia son 404 y los conflictos
son 409. Los códigos se comparan según la restricción exacta existente en
PostgreSQL; no se añade normalización de mayúsculas.

## Jerarquía y concurrencia

Toda escritura de categorías adquiere primero un advisory lock transaccional
por tenant, con espacio `commercecore:catalog:category:` y timeout de tres
segundos. Después se reevaluan autorización, cuenta activa y estado de empresa,
se leen las categorías y se valida la jerarquía. El bloqueo se mantiene hasta
el commit de los cambios y su auditoría. Para que cada sentencia posterior al
bloqueo observe los commits anteriores, la implementación exige READ COMMITTED;
otros niveles se rechazan. Deadlocks, timeout y serialización fallida no se
reintentan automáticamente. Una colisión del hash puede serializar empresas
independientes, pero no permite mezclarlas.

El protocolo protege las escrituras que lo respetan; SQL externo u otro código
privilegiado que lo ignore puede crear ciclos. Una futura operación de suspensión
de Tenant deberá coordinarse con este protocolo: consultar el estado no
proporciona exclusión mutua frente a una suspensión concurrente. Las decisiones
de autorización ya emitidas no cancelan operaciones en curso. Las ediciones se
serializan, pero no existe control optimista contra cambios obsoletos del cliente.

Audit registra actor local existente, tenant, categoría, acción y snapshots de
antes y después en la misma transacción. No se aprovisiona ninguna cuenta para
obtener el actor. Una falla de auditoría revierte la modificación. La
inactivación no elimina productos ni desactiva descendientes; se rechaza si
hay hijos directos activos. Una categoría con padre solo puede reactivarse si
ese padre está activo. El traslado bajo un padre inactivo se rechaza; los
rechazos no registran un cambio ni un evento de auditoría. No se modificó el esquema V1–V4.
