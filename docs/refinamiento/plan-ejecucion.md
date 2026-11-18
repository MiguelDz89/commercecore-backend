# Plan persistente CODEX-013D.2-REF-MASTER

Fuente: `CC-REF-MASTER_2026-11-17.md`. Punto de partida: HEAD `c71d7f0` con cambios de precios preexistentes. Se conservaron el árbol de trabajo y V1–V5. Los resultados históricos de A.1–A.3 constan en la trazabilidad.

| Incremento | Dependencia y criterio de salida | Estado |
|---|---|---|
| A.4 | Contrato de las seis operaciones contrastado con OpenAPI servido y regresión | Pruebas aprobadas |
| B | A.4; unidad, doble habilitación, BigDecimal, control excepcional CC-PROC-001 y migración verificada | Flujo ordinario verificado. Acta excepcional pendiente de confirmación |
| C | Moneda fija, tasas diarias, conversión histórica y permisos separados | Flujo ordinario verificado. Cambio excepcional pendiente |
| D | B/C; tipos, padre válido, atributos y precios independientes | Implementado y verificado mediante pruebas focalizadas. Pendiente revisión arquitectónica y regresión integral |
| E | D; matriz DF-030, archivado terminal, lote atómico y auditoría | En implementación |
| F | E; historia de publicación, permisos separados y lote atómico | Pendiente |
| G | D/F; slug y visibilidad efectiva, datos públicos proyectados | Pendiente |
| H | G; suspensión, lectura de administrador y exportación temporal de un uso | Pendiente |
| I | Permisos de B–H; perfiles aprobados, concesión y revocación auditadas | Pendiente |
| QA | Regresión final, evidencia migratoria y exportación sanitizada | Pendiente |

Riesgos a verificar: importes históricos con moneda discrepante, carreras entre autorización y escritura, conservación de indicadores y cierre del proceso de Failsafe. Usar bases y realm temporales. La referencia persistente del procedimiento excepcional depende de la confirmación arquitectónica prevista por el maestro; el resto de B puede avanzar de forma independiente.

## Reanudación

A.4 verificado el 17/11/2026. OpenAPI servido focalizado: 1 prueba, salida 0 (13:24 PET). Clean test: 90 Surefire, salida 0 (13:27 PET). Clean verify con Failsafe habilitado: 90 Surefire y 3 Failsafe, salida 0 (13:33 PET). Todos con cero fallos, errores y omisiones. La salida 1 del proceso hijo reapareció. Se reprodujo el código al invocar Process.destroy() en un hijo Java aislado. Consultar la incidencia de Failsafe para el alcance de esa comprobación.

B: V6 y modalidad empresarial al alta aprobadas en las dos pruebas focalizadas de la sesión 41184 (13:37 PET, salida 0). Se aplicaron V1–V6 desde una base vacía y se verificó la actualización V5→V6 sin reinterpretar un escalón fraccionario histórico. La modalidad individual y la unidad base ya pasan por Catalog. Las cotizaciones y los escalones usan BigDecimal y comprueban ambas habilitaciones bajo el mismo bloqueo que las modificaciones de producto. El PATCH de unidad rechaza valores comerciales vigentes o historial auditado. La sesión 79668 terminó a las 13:45 PET con salida 0 y siete pruebas focalizadas aprobadas (tres HTTP con Keycloak/PostgreSQL, tres de dominio y una de actualización V5→V6), sin fallos, errores ni omisiones. La confirmación del registro del acta CC-PROC-001 se solicitó por separado. Si aún no está disponible, continuar con C tras verificar B ordinario.

### Diseño para B

- Extender el alta empresarial con modalidad explícita y mantener el valor entero para solicitudes anteriores. La configuración se expone a Catalog mediante un contrato de Tenant conforme a ADR-017.
- Añadir a producto unidad base y modalidad individual. Reutilizar `PRODUCT_CREATE` y `PRODUCT_UPDATE` para configuración ordinaria del producto. Comprobar versión y auditar cambios. Las ediciones de unidad con historial necesitan el flujo de control de cambios.
- Compartir el bloqueo de escritura de Catalog para cambiar configuración y precios, con un contrato de coordinación entre módulos antes de añadir operaciones excepcionales de Tenant. La comprobación de escalones incompatibles y el cambio de modalidad deben compartir transacción y bloqueo.
- Usar `BigDecimal` desde el parser HTTP hasta JDBC y los snapshots de auditoría. Comparar umbrales numéricamente, validar como máximo tres decimales y evitar conversiones de datos históricos. Conservar el mínimo vigente de los escalones y diferenciar la cantidad solicitada del umbral de un escalón.
- Añadir pruebas de cuatro combinaciones de habilitación, umbral exacto con escala distinta, tope y precisión, persistencia, control de cambios y concurrencia. Volver a ejecutar la validación OpenAPI al cambiar los tipos.

### Corte C

V7 añade tasas manuales por empresa/monedas/día y evidencia nullable de coste, conservando filas históricas. El alta de empresa admite omitir moneda con default PEN. Catalog usa la moneda empresarial en productos nuevos y rechaza nuevas cotizaciones y escrituras de precio/coste cuando el producto histórico tiene una moneda discrepante. Mantiene la moneda registrada en las lecturas de precios. La API de tasas usa los permisos independientes PURCHASE_EXCHANGE_RATE_READ y PURCHASE_EXCHANGE_RATE_UPDATE. La conversión multiplica por la última tasa no posterior a la fecha de operación, redondea a dos decimales HALF_UP y conserva el resultado y los datos originales. Los cortes 49291, 1218 y 11361 aprobaron la conversión y la regresión de precios. OpenAPI requirió documentar las fechas ISO y resolver la referencia del UUID del actor en la prueba. La sesión 5339 terminó a las 14:04 PET del 17/11/2026 con cuatro pruebas aprobadas, sin fallos, errores ni omisiones: OpenAPI servido, regresión de precios, actualización V6→V7 y conversión con aislamiento y rollback reales. El cambio excepcional de moneda requiere control formal y análisis para conservar el significado de los registros anteriores.

### Corte D

V8 conserva `product_id` para los cuatro tipos y limita la relación a un nivel mediante la FK compuesta del padre VARIABLE del mismo tenant. Conserva identificador y SKU de los SIMPLE y COMPOSITE existentes. El agrupador carece de SKU y de precios. El servicio verifica bajo el bloqueo de Catalog que el padre no esté archivado. Las operaciones monetarias comparten la identidad de producto y excluyen VARIABLE. La base impide persistir importes y escalones en agrupadores.

Los atributos de combinación se expresan como códigos en minúsculas y valores textuales libres, con igualdad exacta de los valores y unicidad canónica por JSONB dentro de cada agrupador. El techo técnico de 2000 bytes protege el índice de unicidad. Se conserva el esquema configurable de V1. La visibilidad pública de características requerirá una selección explícita en G. El contrato incremental mantiene los permisos Product existentes y conserva tanto SKU como tipo y padre al actualizar.

### Siguiente paso

El 17/11/2026 a las 17:55 PET terminó con salida 0 la ejecución focalizada de `variantsHaveIndependentPricesAndScopedProductManagement`, `simpleProductsHttpOptimisticLockAndTenantIsolation`, `openApiDocumentsAdministrativeEndpointsAndBearer` y `ProductPricingV5MigrationTest`. Se ejecutaron cuatro pruebas con cero fallos, errores y omisiones, usando PostgreSQL y Keycloak temporales. Se verificaron V1–V8 desde cero y la actualización V4→V8, las restricciones de variantes, la compatibilidad histórica y los precios independientes. El primer intento de D había fallado por una expectativa de prueba sobre un campo `total` inexistente en el contrato de cotización. La expectativa corregida comprueba cantidad y precio unitario.

La regresión integral y la verificación empaquetada corresponden a QA posterior. La visibilidad pública y el ciclo de vida de variantes corresponden a incrementos posteriores. El diseño de persistencia de D queda sujeto a revisión del Líder Técnico según la sección 6 del documento maestro. El siguiente incremento es E, sujeto a nueva instrucción.

### Punto de control de E

Se agregó la matriz DF-030 en `Product.Status` y la migración V9 incorpora `PENDING_RECEIPT` y `PRODUCT_STATUS_UPDATE`, sin modificar datos históricos ni conceder permisos. El 17/11/2026 a las 18:34 PET terminó con salida 0 el comando `mvnw.cmd -B -Dtest=ProductStatusTest,ProductPricingV5MigrationTest test`: tres pruebas aprobadas, sin fallos, errores ni omisiones. PostgreSQL 18 temporal aplicó V1–V9 desde una base vacía y V9 desde V8; la repetición en V9 aplicó cero migraciones. La actualización conservó UUID, SKU, estados históricos, precios, costes, relación con variante, atributos y escalón de precios; admitió PENDING_RECEIPT, rechazó un estado ajeno, registró el permiso y no creó concesiones en role_permission. La primera ejecución focalizada detectó una expectativa incorrecta de conteo en la prueba de migración repetida; se ajustó y la repetición completa aprobó.

E.1 dejó validada la migración del ciclo de vida. Los siguientes cortes de E detallan el contrato de entrada, la infraestructura de publicación y el servicio transaccional.

### E.2.1 — contrato de transiciones

`ProductStatusChange` permite motivo nulo donde DF-025 no fija su obligatoriedad. Exige motivo no vacío para INACTIVE y ARCHIVED. ACTIVE sin motivo representa, entre otros casos permitidos, la primera activación. El contrato no decide si esa transición particular es una primera activación; el servicio deberá conocer el estado anterior. No se agregó una segunda matriz de estados.

`mvnw.cmd -B -Dtest=ProductStatusChangeTest test` terminó el 17/11/2026 a las 19:23 PET con salida 0, siete pruebas aprobadas y cero fallos, errores u omisiones. La primera ejecución encontró NPE para elementos nulos y UUID nulo; las validaciones fueron ordenadas para devolver IllegalArgumentException y la repetición aprobó. `git diff --check` quedó sin errores. Archivos de este corte: ProductStatusChange.java, ProductStatusChangeTest.java, plan-ejecucion.md y trazabilidad-refinamiento.md.

El servicio transaccional de E.2.3 utiliza el contrato y valida estado anterior, permisos, dependencias padre/variante y versión vigente.

### E.2.2 — infraestructura de publicación

V10 adelanta infraestructura de F para E.2.3: `published` y `ever_published` comienzan en false. PostgreSQL exige ACTIVE y ever_published para mantener published. El modelo y la lectura JDBC incluyen ambas propiedades; el UPDATE genérico no las modifica. En el código y migraciones V1–V9 no se encontró almacenamiento previo de indicadores. El 17/11/2026 a las 20:11 PET `mvnw.cmd -B -Dtest=ProductPricingV5MigrationTest test` terminó con salida 0 y dos pruebas aprobadas sobre PostgreSQL temporal. Se comprobaron instalación V1–V10, actualización V9–V10, valores iniciales, independencia padre/variante y restricciones SQL.

El 17/11/2026 a las 22:00 PET, `mvnw.cmd -B "-Dtest=ProductPricingV5MigrationTest#upgradesHistoricalDataAndEnforcesPricingConstraints,OidcPlatformAuthorizationTest#simpleProductsHttpOptimisticLockAndTenantIsolation" test` terminó con salida 0: dos pruebas, cero fallos, errores y omisiones. La edición autenticada conservó indicadores y ACTIVE para producto publicado y oculto, modificando nombre e incrementando versión. La comparación inmediatamente antes y después de V10 conservó importes configurados, moneda, escalones, UUID, relaciones y versiones. E.2.2 queda verificada; E.2 y E siguen pendientes.

E.2.3 apaga `published` al salir de ACTIVE y conserva `ever_published`. F establecerá ambos en la primera publicación, conservará `ever_published` al ocultar y exigirá una publicación explícita después de reactivar. Las banderas de variantes se conservan al ocultar o inactivar su agrupador. H mantendrá indicadores y versiones al suspender una empresa.

SYNC-001 identificó en v0.3 reglas aprobadas de DNI/RUC, administrador inicial, límite de administradores, gracia de 24 horas, WebAuthn, alertas, auditoría global y retención de cinco años. Permanecen pendientes la selección de administradores excedentes al vencer el plazo, detalles del identificador, recuperación MFA, evidencia y operación de archivo. El identificador DF-016 tiene significado distinto en el maestro y v0.3; citar documento e ID sin renumerar.

### E.2.3 — servicio transaccional verificado

`ProductAdministration.changeStatus` adquiere el bloqueo actual de Catalog y comprueba `PRODUCT_STATUS_UPDATE`, cuenta activa, membresía y estado de la empresa bajo ese bloqueo. Valida el lote completo, aplica DF-030 y evalúa las variantes de los agrupadores seleccionados según DF-029. Lee en conjunto por empresa y escribe en orden estable de UUID en un lote JDBC condicionado por empresa, producto, versión y estado anterior. La misma escritura actualiza estado, versión, actor, fecha e indicador de publicación. La auditoría registra un evento `PRODUCT_STATUS_CHANGED` por producto, con instantáneas, motivo cuando corresponde y un `correlation_id` común que devuelve el servicio.

El 17/11/2026 a las 22:18 PET, `mvnw.cmd -B "-Dtest=ProductStatusAdministrationPersistenceTest,ProductStatusChangeTest,ProductStatusTest" test` terminó con salida 0. Surefire ejecutó 16 pruebas, con cero fallos, errores u omisiones: ocho de persistencia con Quarkus y PostgreSQL temporal, siete de contrato y una de matriz. En persistencia, la identidad provino de una alternativa CDI exclusiva de pruebas y se ejecutaron RBAC, la escritura JDBC y Audit reales. El rechazo de la segunda inserción de auditoría por PostgreSQL revirtió ambos cambios y la primera auditoría. En la primera ejecución, las ocho pruebas de persistencia dieron error al configurar el sustituto de identidad y las ocho pruebas de dominio y contrato pasaron. Se corrigió la configuración de prueba.

Archivos del corte: `ProductAdministration.java`, `ProductAdministrationService.java`, `ProductStore.java`, `JdbcProductStore.java`, `ProductAuditEvent.java`, `ProductAuditRecorder.java`, `JdbcProductAuditRecorder.java`, `ProductStatusAdministrationPersistenceTest.java`, este plan y la trazabilidad.

El bloqueo de Catalog serializa sus escritores. Identity usa un espacio de bloqueos distinto y aún no hay exclusión común con cambios de estado de empresa de Tenant. La revocación de permisos y la suspensión que se confirmen después de la comprobación podrían coincidir con la escritura. H e I deben coordinar el orden de bloqueos o un protocolo equivalente y comprobar esas carreras. La política transversal de auditoría de intentos rechazados de v0.3 también queda pendiente.

### E.3.1 — exposición REST

`PATCH /api/v1/tenants/{tenantId}/products/{productId}/status` recibe `target`, `version` y `reason`. `POST /api/v1/tenants/{tenantId}/products/status-changes` recibe `target`, `reason` e `items` con `productId` y `version`. Ambos requieren Bearer y el servicio exige `PRODUCT_STATUS_UPDATE`. Devuelven HTTP 200 con el UUID `correlationId` de la auditoría. El destino debe ser PENDING_RECEIPT, ACTIVE, INACTIVE o ARCHIVED. Un destino ajeno a ese conjunto da 400. Una transición inviable desde el estado persistido da 409. El motivo es obligatorio para INACTIVE y ARCHIVED. Se conserva el 404 para denegación o recurso no visible.

**Brecha DF-025A y DF-029.** E.2.3 solo comunica bloqueos como texto de `ProductConflictException`. E.3.1 conserva el 409 genérico de Problem Details. Para presentar bloqueantes, el servicio deberá producir conflictos tipados tras verificar permiso, empresa y pertenencia. Contrato propuesto para revisión: extensión `conflicts` del 409, con `productId`, código de motivo estable y `variantId` cuando una variante del mismo tenant impida archivar al agrupador. Los códigos distinguirían versión obsoleta, transición inviable, variante pendiente y conflicto concurrente de escritura. Si cualquier elemento falta o pertenece a otra empresa, el 404 seguirá siendo indistinguible y carecerá de esta extensión.

El 17/11/2026 a las 22:30 PET, `mvnw.cmd -B "-Dtest=OidcPlatformAuthorizationTest#changesProductStatusThroughBothAuthenticatedRoutes+openApiDocumentsProductStatusChanges" test` terminó con salida 0. Surefire ejecutó dos pruebas, con cero fallos, errores u omisiones, usando PostgreSQL y Keycloak temporales. La prueba HTTP verificó ambas rutas, los UUID de correlación contra Audit, los rechazos de entradas y autenticación y el rechazo de un cambio de estado mediante el PATCH genérico. La segunda prueba contrastó rutas, métodos, seguridad, UUID, esquemas y respuestas con OpenAPI servido. `git diff --check` terminó con salida 0 y avisos de finales de línea de archivos preexistentes.

Archivos del corte: `ProductResource.java`, `ProductStatusRequest.java`, `ProductStatusChangeResponse.java`, `OidcPlatformAuthorizationTest.java`, este plan y la trazabilidad. La exposición REST de E.3.1 está verificada. El contrato tipado propuesto en ese corte se implementa en E.3.2.

### E.3.2 — conflictos de transición

DF-025 mantiene la exigencia de motivo para inactivar y archivar. DF-030 sigue determinando cada transición permitida. Para DF-025A y DF-029, el servicio acumula los conflictos de versión, estado y variantes del lote autorizado antes de escribir. Ambos endpoints documentan el 409 de Problem Details con la extensión `conflicts`, cuyos elementos contienen `productId` y `code`. Solo `UNARCHIVED_VARIANT` añade `variantId` para la variante que impide archivar su agrupador.

Los códigos son `VERSION_CONFLICT` para versión obsoleta, `INVALID_TRANSITION` para destino inviable desde el estado persistido, `UNARCHIVED_VARIANT` para una variante que seguirá sin archivarse y `CONCURRENT_MODIFICATION` cuando una escritura optimista por lote afecta cero filas. La última condición revierte la transacción completa. Los conflictos generales, como la contención del bloqueo de Catalog, conservan el 409 base. Un producto faltante o ajeno hace que todo el lote devuelva 404 sin la extensión, incluso si otros productos del lote presentan conflictos.

El conjunto focalizado se ejecutó con `mvnw.cmd -B "-Dtest=ProductStatusAdministrationPersistenceTest,OidcPlatformAuthorizationTest#changesProductStatusThroughBothAuthenticatedRoutes+returnsAuthorizedProductStatusConflictsWithoutLeakingOrChangingTheBatch+openApiDocumentsProductStatusChanges" test`. El 17/11/2026 pasaron las ocho pruebas de persistencia. Dos aserciones HTTP/OpenAPI fallaron inicialmente al esperar una sola causa en un caso con dos conflictos y al asumir una representación distinta de `allOf`. La repetición del mismo comando con solo el selector `OidcPlatformAuthorizationTest#...` terminó a las 22:45 PET con salida 0, tres pruebas aprobadas y cero fallos, errores u omisiones. La implementación productiva fue la misma en ambas ejecuciones.

La evidencia final combina ocho pruebas de persistencia y tres HTTP/OpenAPI aprobadas con PostgreSQL y Keycloak temporales. Confirma códigos e identificadores, varios conflictos por producto o lote, ambas variantes bloqueantes, igualdad del 404 para IDs ajenos e inexistentes, conservación íntegra de las filas y ausencia de auditoría exitosa ante rechazos. Confirma también el HTTP 200 con correlación de E.3.1 y el esquema 409 de ambas rutas en OpenAPI servido. `git diff --check` terminó con salida 0 y avisos preexistentes de finales de línea.

Archivos del corte: `ProductStatusConflictException.java`, `ProductAdministrationService.java`, `JdbcProductStore.java`, `ProductResource.java`, `ProblemExceptionMapper.java`, `ProductStatusConflictProblem.java`, `ProductStatusAdministrationPersistenceTest.java`, `OidcPlatformAuthorizationTest.java`, este plan y la trazabilidad. E.3.2 está verificada. E.4 mantiene la cobertura exhaustiva, incluida la comprobación de `CONCURRENT_MODIFICATION` en una carrera de escritura.

