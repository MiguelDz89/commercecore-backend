# API de precios

Rutas bajo `/api/v1/tenants/{tenantId}/products/{productId}/prices`, con Bearer OIDC y cuenta local activa. Se aplican permisos de empresa sin concesión implícita de plataforma. Las respuestas de escritura no incluyen importes.

| Método y sufijo | Permiso | Contrato |
|---|---|---|
| GET raíz | PRODUCT_READ | Precio minorista configurado, importe si existe, moneda y versión |
| GET `/tiers` | PRODUCT_PRICE_TIER_READ | Escalones ordenados |
| GET `/quote?quantity=N` | PRODUCT_PRICE_TIER_READ | Precio unitario del mayor umbral alcanzado |
| PUT `/sale` | PRODUCT_PRICE_UPDATE | `{version, retailPrice, tiers:[{minimumQuantity, unitPrice}]}`; sustituye todos los escalones |
| GET `/cost` | PRODUCT_COST_READ | Coste opcional y versión |
| PUT `/cost` | PRODUCT_COST_UPDATE | `{version, cost}`; `cost:null` retira el coste |

La escritura usa bloqueo transaccional de Catalog y condición de versión del producto, y registra snapshots tipados en audit_event dentro de la misma transacción. La auditoría contiene información sensible: no existe todavía una consulta pública de eventos de precios; una futura consulta debe requerir permisos específicos y no basarse únicamente en AUDIT_READ. El código no da por implementado el acceso de lectura de administradores a empresas suspendidas.

El permiso PRICE_MANAGER actual no incorpora automáticamente los permisos nuevos de V5. Quedan pendientes los perfiles explícitos y la concesión posterior por plataforma. La suite ejercita autorización individual, aislamiento, rollback, concurrencia y un recorrido autenticado empaquetado. Una prueba focalizada también comprobó que un error de acceso cruzado no expone un coste ya configurado ni SQL.

## Concesión y cobertura de cierre

Los permisos de V5 existen en el catálogo, pero los perfiles comerciales disponibles no los incluyen. La política de delegación exige que el administrador ya posea cada permiso que intenta conceder; además protege los roles asignados a administradores. Por ello una empresa sin concesión inicial no puede adquirir estos permisos a través de los flujos actuales. Debe diseñarse una operación independiente de plataforma, explícita y auditada; no se debe resolver con SQL manual en producción. Las pruebas empaquetadas utilizan SQL exclusivamente como fixture de su PostgreSQL temporal.

La cotización devuelve el precio unitario; el total se obtiene multiplicando por la cantidad, sin cálculo marginal. Las pruebas comprueban las fronteras 1, 5, 6, 19, 20, 49, 50, 100 y 101. La lectura de escalones y cotizaciones toma el bloqueo de Catalog para evitar observar la sustitución a medias de una configuración.

## Evidencia del contrato servido A.4, antes de B

La prueba `OidcPlatformAuthorizationTest.openApiDocumentsAdministrativeEndpointsAndBearer` consulta `/q/openapi?format=json` y resuelve las referencias locales. Contrasta conjuntos exactos de propiedades y campos requeridos, tipos, formatos, nulabilidad y restricciones. Las rutas reciben `tenantId` y `productId` UUID obligatorios. Todos los errores específicos de operación usan `application/problem+json` con `ProblemDetails`, incluidos `instance` anulable y `correlationId` UUID.

| Operación | Solicitud | Éxito | Errores | Seguridad | Prueba focalizada |
|---|---|---|---|---|---|
| GET raíz | Sin cuerpo | 200 JSON, `configured`, `retailPrice` anulable, `currency`, `version` | 400, 401, 404, 500, 503 | Bearer JWT | Aprobada |
| GET `/tiers` | Sin cuerpo | 200 JSON, lista de `minimumQuantity` y `unitPrice` | 400, 401, 404, 500, 503 | Bearer JWT | Aprobada |
| GET `/quote` | `quantity` obligatorio, int64 ≥ 1 | 200 JSON, `quantity` y `unitPrice` | 400, 401, 404, 409, 500, 503 | Bearer JWT | Aprobada |
| GET `/cost` | Sin cuerpo | 200 JSON, `cost` anulable y `version` | 400, 401, 404, 500, 503 | Bearer JWT | Aprobada |
| PUT `/sale` | JSON obligatorio, `version`, `retailPrice`, `tiers` | 204 sin cuerpo | 400, 401, 404, 409, 500, 503 | Bearer JWT | Aprobada |
| PUT `/cost` | JSON obligatorio, `version`, `cost` requerido y anulable | 204 sin cuerpo | 400, 401, 404, 409, 500, 503 | Bearer JWT | Aprobada |

Los importes admiten valores desde cero y múltiplos de 0.01 por debajo de 10¹⁶. La validación de entrada también rechaza más de dos decimales escritos. OpenAPI expresa el valor numérico. Los umbrales vigentes son enteros entre 2 y 999999999999999. La lista puede estar vacía. Los umbrales deben ser únicos y los precios se mantienen o disminuyen al aumentar la cantidad mínima. Los cuerpos de escritura y elementos de escalones rechazan propiedades desconocidas.

## Contrato B de cantidades y unidad

El alta empresarial admite el campo booleano opcional fractionalQuantitiesEnabled, con valor predeterminado false. Las operaciones de producto devuelven baseUnit y fractionalQuantitiesEnabled. La creación sin unidad conserva UNIT como recuento genérico. Los códigos de unidad admiten A–Z, dígitos y guion bajo, con inicial alfabética y hasta 32 caracteres. El PATCH de producto requiere la versión vigente para modificar la unidad o modalidad individual.

La cotización acepta cantidades positivas de hasta tres decimales, con punto decimal y un máximo de 999999999999999.999. Si la empresa o el producto tienen la modalidad desactivada, se acepta únicamente una cantidad numéricamente entera. Los umbrales nuevos siguen empezando en dos unidades. Los umbrales almacenados son decimales exactos. Se conservan y pueden consultarse los umbrales históricos que ya admitía V5. La cotización de una lista con umbrales fraccionarios incompatibles devuelve 409 hasta corregirla. Los números de escala distinta se comparan por su valor.

Una unidad con precio, coste, escalones o historial auditado devuelve 409 al intentar modificarla por vía ordinaria. Deshabilitar la modalidad individual con escalones fraccionarios también devuelve 409. Una modificación ordinaria inválida conserva la versión y configuración previas. El procedimiento CC-PROC-001 se documenta aparte.

## Contrato C de adquisición y tasas

- GET/PUT /api/v1/tenants/{tenantId}/exchange-rates/{sourceCurrency}/{date} consultan o escriben la tasa exacta del día. Fecha ISO 8601, moneda ISO 4217 y tasa positiva con hasta ocho decimales. La moneda destino es la moneda comercial de la empresa. Permisos PURCHASE_EXCHANGE_RATE_READ y PURCHASE_EXCHANGE_RATE_UPDATE, respectivamente. Registrar o corregir una tasa genera auditoría en la misma transacción.
- PUT /prices/cost conserva version y cost. Los campos opcionales sourceCurrency y operationDate permiten declarar la adquisición en moneda extranjera. En ese caso la fecha es obligatoria y se aplica la última tasa no posterior. Sin tasa aplicable devuelve 409. En moneda local se usa la fecha declarada o la fecha local empresarial y no se aplica conversión.
- GET /prices/cost devuelve cost en currency y acquisition con importe y moneda de origen, fecha de adquisición, fecha y valor de tasa cuando proceda y usuario responsable. Los costes históricos sin evidencia se muestran como acquisition nulo. Corregir una tasa deja intacto el coste y su evidencia anterior. Retirar coste borra su evidencia corriente y la mantiene en auditoría. El redondeo técnico de una adquisición extranjera es decimal a dos posiciones, HALF_UP.
- Los productos nuevos heredan la moneda de empresa. La moneda de los valores históricos se conserva. Cuando existe discrepancia, la cotización y las escrituras monetarias responden 409 hasta reconciliar los registros mediante el procedimiento autorizado.
