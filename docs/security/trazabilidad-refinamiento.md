# Trazabilidad CODEX-013D.2-REF

Fuentes revisadas: CC-ACT-2026-11-17-01 y CC-DOC-002-REF-01, facilitadas por el usuario el 17/11/2026. Sus decisiones funcionales confirmadas se distinguen de las historias y nombres de permiso propuestos. La solicitud posterior autoriza expresamente una lista vacía de escalones, que el acta anterior dejaba pendiente.

Estado vigente según [documento maestro](../refinamiento/CC-REF-MASTER_2026-11-17.md). El registro posterior a la tabla conserva la evidencia histórica de tareas anteriores.

| Requisito | Evidencia y estado vigente |
|---|---|
| DF-006/007, HU-020, A.4 | V5, `PriceSchedule`, servicio, JDBC, seis operaciones. Contrato OpenAPI servido y regresión de A.4 aprobados en el corte anterior a V6. |
| DF-016/031/034, B | V6, modalidad al alta, unidad base, configuración individual y cotización decimal verificadas en pruebas focalizadas. Ejecución excepcional pendiente de confirmación del acta. |
| DF-017/032/033, C | V7, moneda inicial, tasas y evidencia de conversión verificadas. Cuatro pruebas focalizadas aprobadas, incluidas actualización V6→V7, OpenAPI servido, rollback y aislamiento. Cambio excepcional pendiente. |
| DF-007/018/021, HU-021, D | V8 y API administrativa implementadas sobre `product`. Cuatro pruebas focalizadas aprobadas el 17/11/2026 a las 17:55 PET, incluida migración histórica. Visibilidad efectiva pendiente de G y revisión arquitectónica pendiente. |
| DF-019/025/025A/029/030, HU-009, E | Matriz de estados y V9 implementadas. E.1 validado por tres pruebas focalizadas en PostgreSQL temporal. E.2.1 corrige el contrato de motivo con siete pruebas unitarias aprobadas. |
| Maestro DF-010–014 / E.2.2 | V10 y representación de indicadores implementadas. Dos pruebas focalizadas aprobadas el 17/11/2026 a las 22:00 PET, con edición autenticada de producto publicado y oculto y comparación comercial V9–V10; instalación desde cero verificada en ejecución anterior. E.2.2 verificada, E.2 y E pendientes. |
| Maestro DF-025/025A/029/030, E.2.3 | `ProductAdministration.changeStatus`, escritura optimista por lote y auditoría correlacionada. 16 pruebas focalizadas aprobadas el 17/11/2026 a las 22:18 PET, incluidas ocho de persistencia. Verificados permiso, aislamiento, estados, variantes, despublicación, preservación comercial y rollback al fallar la segunda auditoría. E.3 y E.4 conservan el contrato HTTP y las pruebas exhaustivas de carreras. Los bloqueos de suspensión y revocación siguen en H e I. La auditoría transversal de intentos rechazados se mantiene pendiente como requisito de v0.3. |
| Maestro DF-025/025A/029/030, E.3.1 | PATCH individual y POST masivo con Bearer y permiso PRODUCT_STATUS_UPDATE en el servicio. Dos pruebas aprobadas el 17/11/2026 a las 22:30 PET con PostgreSQL y Keycloak temporales. Verificados UUID de correlación y auditoría, rechazos HTTP, protección del PATCH genérico y OpenAPI servido. |
| Maestro DF-025/025A/029/030, E.3.2 | Conflictos tipados en 409 de Problem Details, incluidas las variantes bloqueantes. Ocho pruebas de persistencia y tres HTTP/OpenAPI aprobadas el 17/11/2026 con PostgreSQL y Keycloak temporales. Verificados el 404 indistinguible, la ausencia de escrituras y auditoría de transición tras rechazos, el HTTP 200 previo y el esquema 409 servido. E.4 conserva la cobertura exhaustiva de concurrencia. |
| DF-010–014/020/025A, HU-022A/B, F | Publicación, ocultamiento, republicación y lotes aprobados. Implementación pendiente. |
| DF-008/009/021/022/023, G | Catálogo por slug, lista blanca y reglas de visibilidad aprobados. Implementación pendiente. |
| DF-015/024/025, HU-023/023A/024, H | Suspensión y recuperación por plataforma, lectura y exportación excepcional aprobadas. Implementación pendiente. |
| DF-026/035/036, I | Perfiles y concesión auditada aprobados. Implementación pendiente. |

## Registro histórico anterior al maestro

CODEX-013D.2-REF-A añade pruebas de límites, validación, concurrencia de versión y un recorrido autenticado en el artefacto empaquetado. La suite cubre aislamiento de permisos, concurrencia con cotización y rollback de auditoría; una prueba focalizada comprobó la confidencialidad del error con un coste configurado. La descripción OpenAPI de cuerpos y respuestas sigue pendiente. La concesión de los permisos nuevos a empresas existentes requiere un futuro caso de uso auditado de plataforma.

## Diagnóstico CODEX-013D.2-REF-FULL

La instrucción posterior aprueba recuperar el catálogo al reactivar una empresa respetando los indicadores individuales; sustituye la decisión anteriormente pendiente. No hay implementación de dicha recuperación ni de los incrementos B–G. La nomenclatura y transición de datos históricos, el tratamiento de variantes y los perfiles definitivos siguen pendientes de decisión.

La tabla V5 admite `minimum_quantity numeric(18,3)` mientras el dominio exige `long` y el adaptador utiliza `longValueExact()`. Una fila externa fraccionaria produce error operacional; la API actual acepta solamente cantidades enteras. Antes de admitir fracciones se necesita una decisión comercial y un cambio coordinado, sin reinterpretar datos ni modificar V5.

`tenant.currency_code` puede diferir del valor predeterminado `PEN` de `product.currency_code`. La creación actual de producto no copia la moneda empresarial. Por tanto, no se debe interpretar ese valor como política monetaria aprobada: queda pendiente definir la moneda del producto y la migración o corrección correspondiente antes de habilitar operaciones comerciales multimoneda.

La concesión de permisos de costes y escalones requiere una operación explícita, limitada a perfiles preaprobados y auditada de plataforma. Los perfiles actuales no otorgan esos permisos automáticamente; TENANT_ADMIN debe conservar sus tres permisos exactos. No se ha implementado este mecanismo.

## Punto de control de continuación

Se añadieron anotaciones OpenAPI de respuestas y cuerpos para las rutas de precios. El comportamiento de negocio no se modificó. Antes de dar A.1 por terminado, verificar el esquema OpenAPI generado y completar las pruebas positivas de permisos individuales sobre productos editables. La prueba de concurrencia con configuraciones diferentes fue aprobada en la ejecución focalizada anterior. B–G permanecen pendientes; la exportación final debe generarse después de las pruebas integrales, no confundirla con el compendio anterior. La política monetaria y las cantidades fraccionarias requieren definición; se mantiene V5 sin modificación.

## CODEX-013D.2-REF-A.2 — evidencia parcial

El 17/11/2026 `clean test` terminó con 90 pruebas Surefire aprobadas; `clean verify -DskipITs=false` terminó con 90 Surefire y 3 Failsafe aprobadas, sin omisiones. La prueba HTTP ejercita los cinco permisos aisladamente sobre un producto editable e incluye `/quote`; las pruebas existentes cubren actualizaciones de venta concurrentes, cotización concurrente y fallo de auditoría. El documento OpenAPI servido se comprobó para las seis rutas, códigos seleccionados, seguridad Bearer y esquemas de solicitud.

Queda pendiente verificar de forma exhaustiva los esquemas de respuesta y contenido de error de todas las operaciones, y probar explícitamente modificación simultánea de precio y coste con la misma versión. Por tanto A.1 no se declara cerrado. La incidencia de cierre de Failsafe permanece documentada y no se considera resuelta por BUILD SUCCESS.

## CODEX-013D.2-REF-A.3 — avance

Se añadió una carrera entre la modificación de venta y la de coste con la misma versión; comprueba una sola respuesta 204, una 409, una sola versión adicional y una auditoría del campo de la operación ganadora. `clean test` terminó con BUILD SUCCESS el 17/11/2026. Una primera ejecución de `verify` falló por una aserción de prueba que buscaba el importe en todo el JSON; se corrigió para consultar el campo exacto. La revisión exhaustiva de tipos, campos obligatorios y nulabilidad del OpenAPI generado sigue pendiente, por lo que no se declara cerrado A.3.

Ejecución posterior a la corrección: `clean test` y `clean verify "-DskipITs=false"` terminaron con BUILD SUCCESS el 17/11/2026. La segunda ejecución reportó 90 pruebas Surefire y 3 Failsafe, sin fallos, errores ni omisiones. La carrera entre venta y coste pasó; la verificación exhaustiva de tipos, obligatoriedad y nulabilidad de OpenAPI permanece pendiente. El mensaje conocido de salida del proceso Failsafe no se considera resuelto.

## Decisiones DF-016 a DF-023: punto de control

El documento CODEX-013D.2-REF-FULL aportado por el usuario aprueba cantidades fraccionarias con doble habilitación y tres decimales, moneda comercial por empresa, costes extranjeros, variantes, estados y reglas de publicación y catálogo. Estas decisiones sustituyen la incertidumbre anterior sobre cantidades; no están implementadas todavía. La habilitación de fracciones requiere cambios coordinados en tenant, producto, umbrales, cotización, autorización y migración posterior a V5. No se deben convertir ni redondear registros históricos automáticamente.

Continúan pendientes los perfiles y su concesión, impuestos, agrupación entre variantes, motivos y transiciones adicionales. No hay evidencia de implementación o QA de los incrementos B–H. Se añadieron metadatos de campos requeridos y anulables a los DTO documentales de precios; queda por contrastar el OpenAPI servido y la regresión completa.

## CODEX-013D.2-REF-A.4 — verificación parcial

El 17/11/2026 se corrigió la documentación de los errores 401, 500 y 503 para declarar Problem Details y se documentó `quantity` como entero positivo. La prueba `openApiDocumentsAdministrativeEndpointsAndBearer` comprueba las seis rutas, Bearer, presencia de esquemas exitosos, Problem Details y ausencia de contenido en 204. No verifica exhaustivamente propiedades, obligatoriedad, nulabilidad, formatos ni restricciones; por ello A.4 sigue incompleta. `clean test` y `clean verify "-DskipITs=false"` finalizaron con código 0; verify registró 90 Surefire y 3 Failsafe sin fallos, errores ni omisiones. La incidencia de cierre Failsafe continúa abierta según failsafe-windows.md.


## Maestro, A.4

El OpenAPI 3.1 servido pasó la prueba `openApiDocumentsAdministrativeEndpointsAndBearer` el 17/11/2026 a las 13:24 PET (1 prueba, 0 fallos, 0 errores, 0 omitidas, salida 0). La matriz por operación está en [precios-api.md](precios-api.md). Se trasladó el 409 a las dos escrituras y la cotización, se documentaron los UUID y se corrigió el límite monetario que el generador redondeaba mediante un límite exclusivo equivalente.

El 17/11/2026, sobre el corte A.4 previo a V6, clean test terminó a las 13:27 PET con 90 Surefire y clean verify con Failsafe habilitado terminó a las 13:33 PET con 90 Surefire y 3 Failsafe. Ambos devolvieron 0, sin fallos, errores ni omisiones. Se reprodujo el código 1 del hijo de Failsafe y se aisló el mismo código con Process.destroy() sobre un hijo Java en Windows. El cierre ordenado sigue pendiente de instrumentación. git diff --check pasó.

## Maestro, B en implementación

V6 añade la modalidad empresarial inmutable desde el mantenimiento ordinario y las columnas de unidad base y modalidad individual. El alta admite fractionalQuantitiesEnabled y conserva el comportamiento entero para clientes anteriores. Se usa UNIT para representar el recuento genérico preexistente sin cambiar su valor, importe o unidad inferida. La prueba de actualización parte de V5 y comprueba que un escalón fraccionario histórico conserva su valor y los importes. La ejecución focalizada de migración y alta está en curso. Consultar el [plan persistente](../refinamiento/plan-ejecucion.md).

V6 y el alta pasaron dos pruebas focalizadas el 17/11/2026 a las 13:37 PET, con salida 0. El corte posterior incorpora la doble habilitación para cotizar y escribir umbrales, unidad base por SKU y protección de las modificaciones de unidad o modalidad individual. La prueba de ese corte está en curso. El registro de CC-PROC-001 sigue sujeto a la confirmación del diseño del acta que pide el maestro.

El corte ordinario de B pasó siete pruebas focalizadas el 17/11/2026 a las 13:45 PET: cero fallos, errores u omisiones y salida 0. Se probaron las cuatro combinaciones de habilitación, entrada numéricamente entera con escala decimal, precisión, umbral exacto, persistencia, protección de unidad, conservación V5→V6 y contrato OpenAPI servido. CC-PROC-001 sigue pendiente de confirmación del registro de acta.

## Maestro, C en implementación

V7 agrega tasas de cambio y la evidencia de adquisición. El alta usa PEN por defecto, los productos nuevos heredan moneda empresarial y los discrepantes históricos se conservan y quedan bloqueados para nuevas escrituras monetarias y cotizaciones. Las tasas tienen permisos separados. El coste guarda el importe de origen, el actor y la tasa aplicada en una única transacción con su auditoría. Verificación en curso. Véase el [plan persistente](../refinamiento/plan-ejecucion.md).

La prueba de conversión de C, la regresión de precios y la actualización V6→V7 pasaron en el corte 1218. El OpenAPI falló al describir una fecha del coste. Tras corregir los DTO de transporte, la repetición está en curso en la sesión 11361.

### C — evidencia posterior

La sesión 11361 terminó con un fallo de prueba por un `$ref` del UUID del actor. Tras resolver la referencia, la sesión 5339 terminó a las 14:04 PET del 17/11/2026 con salida 0. Pasaron las cuatro pruebas focalizadas, sin omisiones. Incluyen el contrato servido, el cambio de V6 a V7, la regresión de precios y el aislamiento y rollback de coste y tasas con PostgreSQL y Keycloak temporales.

### D — evidencia focalizada

`mvnw.cmd -B -Dtest=ProductPricingV5MigrationTest,OidcPlatformAuthorizationTest#variantsHaveIndependentPricesAndScopedProductManagement+simpleProductsHttpOptimisticLockAndTenantIsolation+openApiDocumentsAdministrativeEndpointsAndBearer test` terminó el 17/11/2026 a las 17:55 PET con salida 0 y cuatro pruebas aprobadas, sin fallos, errores ni omisiones. La prueba HTTP cubre SKU y combinaciones independientes, precios, costes y escalones por variante, padre incompatible o ajeno, agrupador sin precio, búsqueda, versiones y auditoría. La prueba migratoria comprueba la conservación de datos históricos y restricciones SQL de aislamiento, padre, combinación y agrupador. El ensayo anterior falló por esperar el campo `total` en una respuesta cuyo contrato contiene cantidad y precio unitario. Se corrigió esa expectativa y la repetición pasó. Pendientes la regresión integral, la revisión arquitectónica y la visibilidad pública de G.
