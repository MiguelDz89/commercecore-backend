# Productos simples

Los endpoints autenticados se encuentran bajo `/api/v1/tenants/{tenantId}/products`:
`POST` crea un producto `SIMPLE` en `DRAFT`; `GET` lista o consulta por ID;
y `PATCH /{productId}` modifica nombre, descripción o categoría.
La creación requiere `PRODUCT_CREATE`, la lectura `PRODUCT_READ` y la
modificación `PRODUCT_UPDATE`. El Superadministrador no recibe permisos
empresariales implícitos. Cada caso de uso comprueba la cuenta, membresía,
permiso y empresa activa, con denegación por defecto.

La lista admite `offset` (0 por defecto), `limit` (20; máximo 100), `sku`,
`name`, `categoryId` y `status`. La búsqueda de SKU usa la normalización de
PostgreSQL `upper(btrim(...))`; el índice único por tenant de V1 decide los
conflictos. El SKU no se puede modificar. El DTO no acepta ni expone precio:
`product.sale_price` mantiene el valor predeterminado de la base, que no
representa un precio comercial confirmado. La moneda también conserva el
predeterminado existente hasta que se diseñe el incremento de precios.

PATCH exige `version` entera no negativa y al menos uno de `name`,
`description` o `categoryId`. Omitir un campo conserva su valor; `null` solo
elimina la descripción. El UPDATE exige la versión anterior y la incrementa;
una versión obsoleta o un producto `ARCHIVED` produce 409. No hay eliminación,
activación, publicación, variantes ni cambios de precio en este incremento.

La categoría es obligatoria, activa y del mismo tenant al crear o cambiarla.
Las escrituras de producto adquieren el mismo advisory lock transaccional por
tenant que las categorías, antes de autorización, estado y comprobación de
categoría, y lo mantienen hasta confirmar la auditoría. Así se serializan
frente a desactivaciones que respeten el protocolo. Una desactivación posterior
puede dejar productos históricos asociados a la categoría inactiva, como está
permitido en categorías. El bloqueo exige READ COMMITTED y no protege SQL
externo que lo ignore. Una futura suspensión de Tenant debe coordinarse con el
protocolo; una lectura aislada del estado no evita una carrera.

Los eventos `PRODUCT_CREATED` y `PRODUCT_UPDATED` guardan actor local activo,
tenant, entidad, fecha y snapshots de campos comerciales y versión. El cambio
y su auditoría comparten transacción; si Audit falla, se revierte el producto.
Los snapshots no incorporan precios, tokens ni credenciales. Los errores usan
Problem Details; 404 no distingue falta de acceso y recurso no visible, y 409
representa conflictos de SKU, categoría inactiva, versión o estado archivado.
