# CODEX-013D.2-REF-MASTER — Implementación integral del refinamiento CommerceCore

**Documento:** CC-REF-MASTER-2026-11-17 | **Versión:** 1.0 | **Fecha:** 17/11/2026  
**Fuentes:** CC-ACT-2026-11-17-01, CC-DOC-002-REF-01 y decisiones posteriores ratificadas por Miguel Díaz durante el refinamiento DF-006 a DF-036, incluida DF-025A.  
**Estado:** requisitos funcionales aprobados; implementación, verificación y aceptación independientes pendientes.  
**Responsables:** Miguel Díaz, Líder Técnico/Arquitecto; ChatGPT, PO/QA independiente; Codex, equipo de desarrollo.

## 1. Mandato de ejecución

Actúa como Senior Backend Engineer en el repositorio existente `commercecore-backend` (Java 21, Quarkus, PostgreSQL, Flyway, OIDC/Keycloak, Maven). Implementa, prueba y documenta todo el alcance autorizado de esta especificación mediante incrementos verificables. No es una solicitud de diagnóstico, de plan sin ejecución ni de implementación desde cero.

Esta especificación reúne las reglas confirmadas y prevalece sobre estados funcionales obsoletos de documentos anteriores. Las decisiones arquitectónicas se mantienen bajo responsabilidad del Líder Técnico. No inventes nuevas reglas comerciales, permisos ilimitados, contratos incompatibles ni datos de producción.

### 1.1 Modo de trabajo y persistencia

1. Lee las instrucciones AGENTS.md aplicables y la configuración, código, migraciones, pruebas y documentación existentes. Inspecciona el estado de Git sin revertir cambios. Confirma el último punto de ejecución mediante evidencia, no solo mediante el texto de informes anteriores.
2. Conserva este documento en `docs/refinamiento/CC-REF-MASTER_2026-11-17.md` si todavía no está en el repositorio. Crea o actualiza de forma conservadora un plan persistente de ejecución en `docs/refinamiento/plan-ejecucion.md` con tareas A–I y QA, dependencias, criterios, riesgos, estado y próximo paso. Si ya existe un plan equivalente, reutilízalo.
3. Trabaja secuencialmente por incrementos; contrasta cada cambio con la matriz de requisitos, implementa, ejecuta pruebas focalizadas, corrige fallos, registra evidencia y continúa al siguiente incremento autorizado. No vuelvas a implementar código que ya cumple los criterios.
4. Si se alcanza un límite de turno, contexto o presupuesto, deja el punto de continuación exacto en el plan: cambios realizados, pruebas realmente ejecutadas, pendientes, bloqueos y primer comando o archivo a revisar. Reanuda desde ahí en la siguiente iteración. No repitas cambios ni reinicies el proyecto.
5. Si un requisito presenta una decisión funcional genuinamente no resuelta, registra la pregunta concreta y su impacto, no inventes la respuesta. Continúa los incrementos independientes que no dependan de ella. Detente ante un bloqueo real, una restricción de autorización o un riesgo de integridad que no se pueda resolver dentro del alcance.
6. Actualiza `docs/security/trazabilidad-refinamiento.md` y los documentos API pertinentes en cada punto de control. Conserva los informes históricos; corrige estados actuales contradictorios, sin presentar resultados previos como pruebas de cambios posteriores.
7. Diferencia: pendiente, en implementación, implementado sin verificar, pruebas aprobadas, bloqueado, y aceptado por QA independiente. Codex puede informar la finalización técnica, no aprobarse a sí mismo como PO/QA.

### 1.2 Restricciones no negociables

- Preservar cambios existentes (incluidos los no confirmados en Git). No hacer reset, checkout destructivo, commits, push ni despliegues.
- No modificar V1–V5 ni las migraciones posteriores ya aplicadas. Crear migraciones Flyway nuevas correlativas y validar tanto instalación desde cero como actualización desde la versión previa.
- No conectarse a `commercecore_dev`, Neon ni otras bases persistentes/productivas. Usar PostgreSQL y Keycloak temporales para pruebas.
- No exponer secretos, credenciales, tokens reales, datos de otras empresas ni información privada.
- Mantener arquitectura modular, convenciones de código, Java 21 y seguridad existente. Evitar reescrituras generales.
- No ampliar automáticamente `TENANT_ADMIN`. Conservar sus permisos existentes exactos (`TENANT_READ`, `TENANT_MANAGE`, `USER_MANAGE`); verificar la nomenclatura real en el código.
- No incorporar inventario, existencias, compras, órdenes de compra, recepciones automáticas, movimientos, gestión tributaria, ventas multimoneda ni facturación de servicios adicionales. El perfil INVENTORY_MANAGER se definirá en futuros sprints.
- No eliminar físicamente productos, variantes ni sus referencias históricas.
- No debilitar validaciones, omitir pruebas ni modificar expectativas para conseguir resultados verdes artificiales.
- No bloquear desarrollo por falta de la skill de exportación; realizar el procedimiento alternativo de la sección 12 al final.

## 2. Estado inicial conocido y diagnóstico

El último informe de Codex indica cambios parciales en OpenAPI de precios, no la implementación integral. Los incrementos de cantidades, moneda, familias/variantes, estados, publicación, catálogo, ciclo empresarial y perfiles seguían pendientes según dicho informe. Verificar el estado real antes de tocar código.

- A.1–A.3: reportados ensayos de concurrencia con dos configuraciones diferentes, concurrencia venta/costo con la misma versión (un éxito y un conflicto, un incremento, datos del ganador y una auditoría), permisos separados sobre producto editable, cotización concurrente, rollback real por fallo de auditoría y aislamiento/confidencialidad. Confirmar su vigencia.
- Última regresión reportada antes de posteriores cambios: 90 Surefire y 3 Failsafe sin fallos ni omisiones. No asumir que ese resultado corresponde al estado actual.
- A.4: comprobar exhaustivamente el OpenAPI generado, incluida respuesta/error, tipos, obligatoriedad, nulabilidad y seguridad de seis rutas de precios; la última prueba focalizada no lo cubría completamente.
- Diagnósticos previos: `minimum_quantity numeric(18,3)` frente a `long`/ `longValueExact()`; discrepancia potencial entre moneda de empresa y valor PEN del producto; nuevos permisos no alcanzables por aprovisionamiento regular; posible carrera de cambios de autorización/suspensión con operación de negocio; mensaje conocido de cierre de Failsafe.
- Referencias de código reportadas: `PriceSchedule`, `ProductPriceAdministration`, `JdbcProductPriceStore`, `ProductPriceResource`, `OidcPlatformAuthorizationTest`, `SprintOnePackagedIT`, `TenantStatusQuery`, `CommercialProfile`. Validar nombres y evolución en el repositorio.

## 3. Precios, impuestos y cotización (DF-006, DF-007, DF-008, DF-017, DF-022, DF-027, DF-028)

### 3.1 Precios minoristas y mayoristas

- Precio de lista = precio minorista. El costo de adquisición es privado, separado del precio comercial y sujeto a permisos específicos.
- Escalones por producto vendible/SKU/variante; número flexible, se permite la lista vacía. No imponer un máximo comercial nuevo sin aprobación; un límite técnico defensivo debe explicarse y documentarse.
- Si la cantidad solicitada es inferior al primer umbral, utilizar el precio de lista.
- Desde un umbral inclusive, seleccionar el mayor umbral alcanzado y aplicar ese precio unitario a TODA la cantidad. No cobrar por tramos acumulativos.
- Cada variante tiene configuración de precios, costo y escalones independiente. No agrupar cantidades entre variantes o SKU, aunque pertenezcan al mismo agrupador.
- Los precios mayoristas no superarán el precio de lista ni aumentarán a medida que crece la cantidad. Mantener validaciones monetarias compatibles, precio cero permitido, umbrales válidos y únicos.
- Un salto de umbral puede reducir el importe total; no prohibirlo ni aplicar recargos compensatorios.
- El precio de venta representa el precio final al cliente e incluye los impuestos aplicables. No calcular IGV automáticamente ni implementar cupones, descuentos adicionales, promociones o gestión tributaria en el MVP.

| Cantidad | Precio de lista S/ 100, desde 5 S/ 90, desde 10 S/ 80 | Total |
|---:|---:|---:|
| 1 | 100 | 100 |
| 4 | 100 | 400 |
| 5 | 90 | 450 |
| 9 | 90 | 810 |
| 10 | 80 | 800 |
| 12 | 80 | 960 |

### 3.2 Sin precio de lista (DF-022)

- Se permite publicar una entidad vendible sin precio de lista configurado (NULL). El catálogo indicará «Consultar precio».
- NULL no equivale a cero; un precio de cero configurado se muestra como cero.
- Sin precio de lista, informar que no se puede efectuar una cotización automática. No sustituir NULL por cero ni revelar valores privados como alternativa.
- El producto agrupador no vendible no tiene precio propio: la información pública de precio corresponde a variantes efectivamente visibles.

### 3.3 Seguridad y consistencia

- Mantener lectura/escritura separadas de precio minorista, escalones, cotización y costo de acuerdo con sus permisos. No revelar el costo en respuestas de venta, cotización, errores, auditoría accesible sin permiso o catálogo público.
- Escrituras y auditoría transaccionales con control de concurrencia consistente. Una modificación rechazada no cambia precios, costo, escalones, versión ni auditoría exitosa.
- Probar cantidades inferiores, exactas y superiores a umbrales, lista vacía, límites monetarios, precio cero, ausencia de precio, SKU aislados y carreras de modificaciones distintas.

## 4. Cantidades, medidas y control de cambios (DF-016, DF-031, DF-034)

### 4.1 Cantidades fraccionadas

- Permitir cantidades fraccionadas bajo doble habilitación: configuración general de empresa y configuración individual del producto vendible/variante. Si una está deshabilitada, solo cantidades enteras.
- Cada producto vendible dispone de unidad de medida base; su costo, precio de lista, umbrales y cotización se interpretan en dicha unidad.
- Máximo tres decimales de cantidad. Punto como separador decimal (150 cm = 1.5 metros). No confundir separador decimal y miles.
- Usar BigDecimal en Java y NUMERIC(18,3) en PostgreSQL para cantidades; no double/float ni conversiones a long que pierdan fracciones.
- Aceptar cantidades válidas dentro de los límites de precisión y rango aprobados; rechazar precisión excesiva o valores inválidos sin redondeos silenciosos. Mantener compatibilidad con cantidades enteras (5 y 5.000 representan la misma cantidad).
- Los umbrales mayoristas se expresan en la unidad base y respetan las mismas reglas de fraccionamiento. La comparación a igualdad debe ser numérica, no depender de escala de BigDecimal.
- Preparar los contratos para el futuro inventario en unidad base, sin implementar inventario ni conversiones de compras/movimientos en este MVP.

### 4.2 Configuración empresarial: única durante el alta

- La empresa define la modalidad de cantidades al inicializarse mediante análisis previo de negocio.
- Después del alta, rechazar modificaciones ordinarias de la configuración empresarial, incluso mediante API, edición genérica o perfil de administración empresarial. No abrir un simple interruptor de cambio posterior.
- Solo el superadministrador podrá autorizar y ejecutar una modificación excepcional. Habilitar posteriormente es más sencillo; deshabilitar requiere acta y análisis de impacto.
- Deshabilitar el fraccionamiento en un producto/variante por vía ordinaria no puede dejar umbrales fraccionados incompatibles. Si los hay, exigir corrección autorizada antes; conservar históricos.
- No convertir, truncar ni redondear cantidades históricas en migraciones o cambios de configuración.

### 4.3 Procedimiento obligatorio de deshabilitación excepcional (CC-PROC-001)

1. El administrador empresarial solicita el cambio y justifica su necesidad.
2. El superadministrador/soporte analiza impacto, identifica y contabiliza productos y variantes afectados, unidades, configuraciones y escalones fraccionados incompatibles.
3. Se genera un acta de cambio con identificación de empresa, solicitante/fecha, motivo, modalidad anterior/nueva, número y relación de afectados, criterio de corrección, compromiso del cliente y responsable de corregir.
4. El cliente se compromete a corregir los productos y los usuarios autorizados realizan correcciones auditadas sin alterar históricos.
5. Se verifica nuevamente toda la empresa. Si permanecen incompatibilidades, rechazar el cambio y mostrar los productos afectados y sus motivos.
6. Solo el superadministrador aprueba y ejecuta el cambio excepcional, vinculado al acta; el registro y la auditoría se confirman conjuntamente.
7. Conservar evidencias, resultado, actor, fecha/hora, valores previos/nuevos y referencia al acta. Registrar el cierre.

Este servicio podrá cobrarse por separado; NO se implementa su facturación. Documentar íntegramente el proceso en `docs/operations/deshabilitacion-fraccionamiento.md`. No se requiere un sistema general de gestión documental; un mecanismo mínimo de referencia/registro de control de cambios es suficiente si el Líder Técnico confirma su diseño. Impedir una carrera que introduzca nuevos umbrales incompatibles entre verificación y cambio.

### 4.4 Cambio de unidad de medida (DF-034)

- Permitir modificación ordinaria de unidad solo mientras no existan precios, costos, escalones ni historial comercial asociado, con autorización y auditoría.
- Si existe alguno, exigir informe de impacto, control de cambios y aprobación del superadministrador.
- No reinterpretar automáticamente importes o cantidades (S/ 20 por metro no equivale a S/ 20 por centímetro).
- Conservar históricos y evidencia de la conversión/corrección expresamente autorizada; no inventar factores ni políticas de conversión.
- Si el flujo completo de cambio excepcional requiere definición de migración histórica, mantener el rechazo seguro hasta contar con el diseño aprobado.

## 5. Moneda comercial y tipos de cambio (DF-017, DF-032, DF-033)

- Cada empresa elige su moneda comercial al inicializarse; por defecto sol peruano (PEN, símbolo S/). Todos los precios de venta y escalones de la empresa utilizan esta única moneda.
- Una vez inicializada, la moneda queda fija en mantenimiento ordinario. Cualquier cambio posterior requiere control formal de cambios, análisis de impacto y autorización del superadministrador. No relabelar ni convertir importes históricos de forma automática. No implementar ventas multimoneda.
- Los productos nuevos utilizan la moneda comercial empresarial; evitar la discrepancia con PEN por defecto del producto cuando el tenant tiene otra moneda.
- Los costos de adquisición pueden originarse en moneda extranjera. Registrar manualmente tipos de cambio diarios por empresa, fecha y par de monedas (origen/destino), con permiso y auditoría. La tasa expresa cuántas unidades de moneda local corresponden a una unidad de moneda origen.
- Para convertir, seleccionar por fecha el último tipo de cambio registrado no posterior a la fecha de operación (máxima fecha <= fecha operación), no necesariamente del día anterior. No utilizar tasas futuras ni exigir que exista la del día cuando hay una anterior.
- Si no existe ninguna tasa aplicable, rechazar el registro de costo extranjero. No inventar una tasa, buscar una fuente automática ni usar una tasa de otra empresa. Costo en moneda local no requiere conversión extranjera.
- Conservar importe original, moneda original, fecha de operación, fecha de la tasa seleccionada, valor de la tasa aplicado, importe convertido a moneda local, actor y auditoría. Modificar una tasa posterior o corregirla no recalcula costos históricos.
- Ejemplo ilustrativo: operación 17/11/2026, USD 100, último TC USD/PEN registrado 16/11/2026 = 3.730; costo convertido S/ 373.00. La fecha real del TC utilizado debe quedar registrada.
- Aislar mantenimiento y lectura de costos/tasas según autorización. Usar precisión decimal y política monetaria coherente con contratos existentes; documentar cualquier criterio de redondeo técnico y escalar cambios comerciales no aprobados.
- Analizar registros monetarios históricos discrepantes antes de migrar; no cambiar su significado.

## 6. Productos, variantes y atributos (DF-007, DF-010, DF-018, DF-021)

Tipos funcionales:

| Tipo | Función |
|---|---|
| SIMPLE | Producto vendible por sí mismo, con SKU, precio y configuración propios. |
| VARIABLE | Producto principal agrupador; no vendible, sin precio, costo o inventario propios. |
| VARIANT | Producto vendible perteneciente a un VARIABLE, con SKU, atributos y configuración propios. |
| COMPOSITE | Producto vendido como conjunto; respetar el alcance ya existente. No implementar BOM/inventario nuevos. |

- Evaluar y reutilizar el modelo de producto existente y la relación autorreferenciada `parent_id`, propuesta para compartir infraestructura de precios, auditoría y seguridad. El diseño definitivo corresponde al Líder Técnico.
- Cada variante posee SKU, atributos, descripción/datos comerciales pertinentes, unidad, configuración fraccionada, precios, costo, escalones, estado, publicación e historial independientes.
- Atributos extensibles (por ejemplo talla y color) sin multiplicar columnas específicas de cada rubro. Aplicar restricciones coherentes de unicidad de SKU y combinaciones de atributos dentro del alcance de empresa/producto.
- Solo un agrupador válido de la misma empresa puede ser padre de una variante. Impedir relaciones cruzadas entre empresas, ciclos, padres inexistentes y relaciones incompatibles; no crear nuevas variantes bajo un agrupador archivado.
- Si un VARIABLE carece de variantes visibles, permanece oculto en catálogo, sin cambiar su indicador. Si vuelve a existir al menos una variante visible, recupera la visibilidad automáticamente siempre que se mantengan las demás condiciones.
- Conservar la información de productos existentes en migraciones, sin inventar familias o reinterpretar SKU históricos.

## 7. Ciclo de vida, archivado y operaciones masivas (DF-009, DF-011–DF-014, DF-019, DF-025, DF-025A, DF-029, DF-030)

### 7.1 Estados aprobados y matriz de transición

| Estado | Significado | Destinos permitidos |
|---|---|---|
| DRAFT | Borrador; inicial | PENDING_RECEIPT, ACTIVE, ARCHIVED |
| PENDING_RECEIPT | Solicitado al proveedor, aún no recibido, estado manual | ACTIVE, INACTIVE, ARCHIVED |
| ACTIVE | Comercializable | INACTIVE, PENDING_RECEIPT, ARCHIVED |
| INACTIVE | Deshabilitado | ACTIVE, PENDING_RECEIPT, ARCHIVED |
| ARCHIVED | Archivado definitivo, borrado lógico | Ninguno |

- No regresar a DRAFT. ARCHIVED es terminal; no restaurar ni eliminar físicamente. Conservar relaciones y datos históricos. No usar ARCHIVED como estado de ocultamiento temporal.
- Solo ACTIVE puede habilitar publicación. Toda salida de ACTIVE deshabilita su propio indicador en la misma transacción. Volver a ACTIVE no publica automáticamente.
- El estado pendiente es manual; no representa pedidos de compra/recepciones automáticas ni obliga a registrar stock.
- Prohibir bypass de estado o archivado mediante actualización genérica, importación u otros endpoints.
- Auditar cada transición: empresa, producto/variante, usuario, estado anterior/nuevo, fecha/hora, motivo cuando sea exigible, cambio del indicador de publicación y correlación de operación.

### 7.2 Archivado de agrupadores (DF-029)

- No realizar archivado implícito en cascada.
- Archivar un VARIABLE es permitido solo si todas sus variantes están archivadas o quedarán archivadas explícitamente en la misma operación masiva válida.
- Si queda alguna no archivada, rechazar e identificar las variantes que bloquean la operación, sin revelar datos de terceros.
- Se permite archivar simultáneamente agrupador y todas sus variantes mediante selección explícita, con auditoría individual.
- Ningún registro archivado se reactiva. Mantener datos para consultas históricas autorizadas.

### 7.3 Operaciones masivas atómicas (DF-025A)

- Soportar individual y masivamente: activar, inactivar, cambiar a pendiente de recepción, publicar, ocultar, republicar y archivar.
- Selección explícita de productos/variantes y confirmación de acción. Validar todas las entidades, estados, pertenencia, permisos, restricciones de padre/hijo y versiones cuando corresponda.
- Motivo común obligatorio cuando aplique. Identificador de lote común (batch_id o equivalente) más auditoría individual por cada entidad.
- Alternativa aprobada: todo o nada. Si 1 de 20 no cumple, no se modifica ninguno. Informar cada producto que impide ejecutar y el motivo, sin exponer recursos de otra empresa.
- Validar el estado final conjunto al archivar un padre y sus variantes seleccionadas. Impedir que carreras concurrentes invaliden la comprobación. Ejecutar cambios y auditorías en una sola transacción; fallo de cualquier modificación o auditoría implica rollback total.
- No introducir ejecución parcial como modalidad alternativa del MVP.

### 7.4 Motivos requeridos (DF-025)

| Operación | Motivo |
|---|---|
| Suspender empresa | Obligatorio |
| Reactivar empresa | Obligatorio |
| Aprobar/rechazar exportación excepcional | Obligatorio |
| Archivar producto/variante | Obligatorio |
| Inactivar producto/variante | Obligatorio |
| Republicar producto/variante | Obligatorio |
| Ocultar voluntariamente producto/variante | Obligatorio |
| Primera publicación | No obligatorio |
| Primera activación | No obligatorio |

Todas las operaciones se auditan y requieren su autorización correspondiente, aun cuando el motivo no sea obligatorio. No exigir un flujo de doble aprobación en estas operaciones de producto: basta el permiso funcional adecuado.

## 8. Publicación y catálogo público (DF-008–DF-014, DF-020–DF-023)

### 8.1 Indicadores y acciones

- Estado y publicación son conceptos independientes. Persistir indicador individual de publicación e información suficiente para distinguir primera publicación de republicación (el modelo exacto es decisión técnica).
- Primera publicación de entidad activa exige PRODUCT_PUBLISH; ocultamiento voluntario apaga indicador, mantiene estado activo y exige PRODUCT_UNPUBLISH; republicar una entidad anteriormente publicada, ocultada o desactivada exige PRODUCT_REPUBLISH. Cambiar estado requiere PRODUCT_STATUS_UPDATE o su equivalente aprobado.
- La publicación inicial no puede utilizarse para evadir la autorización especial de republicación. Crear o editar tampoco permite eludir controles. Las mismas reglas se aplican a productos y variantes.
- Inactivar un VARIABLE apaga únicamente su propio indicador; ocultar el principal tampoco altera los indicadores de variantes. Variantes conservan sus estados e indicadores, ocultas efectivamente mientras el principal no cumpla. Republicar el principal recupera visibilidad de las variantes activas/publicadas conservadas; no republicar cada variante por separado si nunca se apagó su propio indicador.
- Inactivar una variante apaga su indicador propio; reactivar no publica; si ya se publicó anteriormente, exige republicación explícita.
- Auditar cambios directos de estado/publicación, actor, valores anterior/nuevo, fecha/hora, motivo y correlación. No registrar falsa modificación individual de indicadores que solo se ocultan por visibilidad derivada.
- Publicar sin stock registrado está permitido; no presentar afirmaciones de existencias no verificadas.

### 8.2 Visibilidad efectiva

| Entidad | Condiciones |
|---|---|
| SIMPLE/COMPOSITE | Empresa habilitada, entidad ACTIVE y publicada. |
| VARIANT | Empresa habilitada; padre ACTIVE y publicado; variante ACTIVE y publicada. |
| VARIABLE | Empresa habilitada; principal ACTIVE y publicado; al menos una variante cumple su propia visibilidad. |

Todas las condiciones son simultáneas. Si alguna no se cumple, el registro no es visible en listados, filtros, búsquedas ni acceso directo.

- Cuando el principal se oculta únicamente por falta de variantes visibles, conservar su indicador; recupera visibilidad automáticamente al aparecer una variante válida. No exigir republicación del principal si su indicador nunca cambió.
- Las consultas públicas usan DTO/proyecciones de lista blanca; no reutilizar serialización de modelos administrativos. Mostrar exclusivamente datos autorizados y precio minorista (o «Consultar precio»). Excluir costo, escalones, proveedores, tasas de adquisición, datos internos y otros sensibles también de respuestas API y errores. No exponer una cotización mayorista mediante API pública.

### 8.3 Identificador público de empresa (DF-023)

- Slug globalmente único, solo minúsculas, dígitos y guiones; independiente de UUID interno y razón social.
- Ejemplos: `/catalogo/motorepuestos-diaz`, `/catalogo/bellas-sorpresas`.
- Fijo en el MVP, no cambia cuando cambia la razón social, suspensión o reactivación. No reasignarlo a otras empresas.
- Resolver catálogo por slug, aplicando los mismos controles de seguridad/visibilidad en todas las rutas públicas. Evitar colisiones concurrentes.
- Prever asignación controlada para empresas existentes sin publicar automáticamente registros ni sobrescribir identificadores históricos. Si se requiere una decisión sobre un slug histórico específico, documentar y pedir autorización.

## 9. Ciclo de empresa y exportaciones excepcionales (DF-015, DF-024, DF-025)

### 9.1 Suspensión y reactivación

- Solo un superadministrador de plataforma puede autorizar y ejecutar suspensión/reactivación en el MVP. Un único superadministrador es suficiente; no se exige segundo aprobador.
- Motivo obligatorio y auditoría administrativa, con estado previo/nuevo, actor, fecha/hora y empresa. No alterar destructivamente información.
- La suspensión deshabilita el catálogo público completo, incluidas búsquedas y acceso directo. Actúa por encima de los indicadores individuales de publicación. Conservar estados e indicadores, no ejecutar cambios masivos de productos.
- Usuarios operativos no administradores pierden acceso al contexto de la empresa suspendida, incluso con sesiones/tokens previamente emitidos. El administrador de empresa con membresía y cuenta válidas conserva exclusivamente lecturas autorizadas; los permisos de escritura que posea no anulan el modo lectura.
- Al reactivar, restablecer automáticamente catálogo y accesos permitidos según estados, indicadores, membresías y permisos vigentes. No recuperar permisos revocados ni indicadores individuales ya apagados. No exigir republicación masiva.
- No afectar otras empresas del mismo usuario. Validar el estado y autorizaciones actuales al ejecutar operaciones; evitar carreras de suspensión/revocación con escrituras en curso, coordinando las transacciones sin debilitar seguridad.

### 9.2 Modo lectura del administrador suspendido

| Operación | Regla |
|---|---|
| Consultar productos y variantes, incluidos históricos autorizados | Según permiso de lectura |
| Consultar precios y escalones | Según permisos correspondientes |
| Consultar costos privados | Solo con permiso explícito de costo |
| Consultar usuarios y roles | Según permisos de consulta |
| Consultar auditoría | Según permiso de lectura de auditoría |
| Crear, editar, archivar, cambiar estado, publicar, republicar, importar | Denegado |
| Administrar usuarios, membresías y permisos | Denegado |
| Exportar información | Denegado por defecto; solo excepción definida abajo |
| Acceder al catálogo público | Denegado |

No habilitar lectura a operadores por tener permisos de consulta. La excepción de solicitud y exportación autorizada no habilita modificaciones empresariales; las actuaciones de plataforma y auditorías propias del flujo son independientes.

### 9.3 Exportación excepcional de empresa suspendida (DF-024)

- El administrador solicita una exportación específica. Cada exportación requiere aprobación individual de un superadministrador. No es un permiso general de exportaciones ilimitadas.
- Autorización temporal vinculada a solicitante, empresa y solicitud; mínimo 1 hora, máximo 3 horas, inicio/vencimiento explícitos. El permiso funcional propuesto es TENANT_SUSPENDED_EXPORT, con grant temporal específico y control de uso; nunca basta un permiso permanente.
- La aprobación sirve para esa exportación: una vez utilizada no permite otra. Revocable antes de utilizarse; vencimiento automático. Verificar vigencia y consumo en backend y manejar carreras de consumo para impedir duplicación.
- La autorización no reactiva la empresa, no habilita escritura ni otorga lecturas sensibles adicionales. Comprobar membresía, cuenta, contexto y permisos vigentes; no permitir que otra persona o empresa use el grant.
- Solo exportar nombre del producto, precio de lista y características comerciales expresamente permitidas. Aplicar lista blanca también en variantes, columnas, metadatos y respuestas; no incluir SKU u otros campos no aprobados como columnas adicionales, ni atributos privados disfrazados de características.
- Prohibidos: costo de adquisición, proveedores, escalones mayoristas, inventario y cualquier otro dato sensible, incluso si el administrador está autorizado a consultarlo por otras vías.
- Auditar solicitud, aprobación, rechazo, revocación y exportación/consumo; motivos obligatorios en aprobación/rechazo. Registrar identidad de solicitante y superadministrador, empresa, timestamps, alcance autorizado e identificador de solicitud, sin volcar datos sensibles.
- No implementar exportación general sin aprobación ni permitir reutilización del archivo mediante un endpoint de descarga sin control de autorización. Definir el formato técnico y manejo de reintentos de forma segura, sin ampliar datos/alcance.

## 10. Perfiles, permisos y delegación (DF-026, DF-035, DF-036)

### 10.1 Perfiles aprobados

| Perfil | Responsabilidad funcional |
|---|---|
| TENANT_ADMIN | Administrar empresa y usuarios; delegar permisos autorizados; conservar exactamente permisos base existentes. |
| CATALOG_MANAGER | Crear/editar y consultar productos/variantes; estados, archivado y operaciones masivas del ámbito catálogo. |
| PRICING_MANAGER | Consultar/modificar precios de venta, administrar escalones mayoristas. |
| COST_MANAGER | Consultar/modificar costos y registrar/corregir tipos de cambio. |
| PUBLICATION_MANAGER | Publicar, ocultar y republicar con los permisos separados correspondientes. |
| SALES_OPERATOR | Consultar productos y realizar cotizaciones autorizadas; no administra precios ni costos. |
| INVENTORY_MANAGER | Reservado para futuro sprint, sin privilegios ni funcionalidades de inventario en este desarrollo. |

- Se pueden combinar perfiles. Segregar lectura y escritura de costos respecto de ventas. TENANT_ADMIN no obtiene acceso automático a costos ni nuevos permisos comerciales.
- Mantener los cinco permisos de precios reportados: PRODUCT_READ, PRODUCT_PRICE_TIER_READ, PRODUCT_COST_READ, PRODUCT_PRICE_UPDATE, PRODUCT_COST_UPDATE. Verificar su implementación exacta y asignar únicamente lo necesario para cada responsabilidad. La cotización debe entregar el resultado comercial autorizado sin costos y sin ampliar acceso no aprobado a configuración de escalones.
- Permisos de estado/publicación propuestos: PRODUCT_STATUS_UPDATE, PRODUCT_PUBLISH, PRODUCT_UNPUBLISH, PRODUCT_REPUBLISH. Implementar nomenclatura coherente con RBAC existente; separar otras capacidades sensibles según responsabilidad y documentar matriz acción-permiso-perfil.
- No inventar permisos globales o wildcard. Las pruebas deben demostrar autorización positiva y negativa por permiso, no solo por rol amplio.

### 10.2 Aprovisionamiento y delegación

1. El superadministrador habilita explícitamente para cada empresa los perfiles comerciales preaprobados/contratados.
2. El superadministrador concede inicialmente los perfiles autorizados a usuarios designados, tanto para empresas nuevas como existentes, mediante operación de plataforma limitada y auditada. No utilizar SQL manual como solución regular.
3. El administrador empresarial solo delega permisos que posee y está autorizado a conceder, para la misma empresa; no puede habilitar perfiles no autorizados, escalar a superadministrador ni autoasignarse permisos que no posee.
4. Revocaciones y concesiones se auditan. Verificar estado/membresía/permiso vigente en cada operación; tokens previos no prevalecen sobre revocación/suspensión.
5. Superadministrador y permisos de plataforma son distintos de la autorización en contexto empresarial. No permitir al administrador suspender o reactivar su empresa ni gestionar excepciones de exportación como aprobador.

Pruebas: provisionamiento real de una empresa existente y una nueva, concesión inicial auditada, delegación válida e inválida, revocación con sesión previa, aislamiento, perfiles separados, suspensión de empresa y conservación de los tres permisos exactos de TENANT_ADMIN. Fixtures solo para pruebas temporales, no mecanismo comercial.

## 11. Plan de ejecución y criterios de aceptación (actualizar según estado real)

| Incremento | Trabajo concreto | Evidencia mínima de cierre |
|---|---|---|
| A.4 | Cierre de precios existentes y contrato OpenAPI | Seis operaciones, esquema servido exhaustivo de solicitudes/respuestas/errores, seguridad, tipos/formatos/obligatoriedad/nulabilidad/$ref, pruebas HTTP y regresión. |
| B | Cantidades fraccionadas, medidas y configuración inicial/control de cambios | Tenant y SKU ambos habilitados, validaciones BigDecimal, umbrales decimales, compatibilidad enteros, bloqueo de cambios ordinarios, procedimiento CC-PROC-001, autorización/acta e incompatibilidades. |
| C | Moneda y tipos de cambio | Moneda fija por tenant, default PEN, precio local, TC vigente o último anterior, rechazo sin tasa, instantánea de costo histórico, auditoría, seguridad. |
| D | Productos y variantes | Migración conservadora, agrupador no vendible, SKU y atributos independientes, aislamiento y cotización por SKU. |
| E | Estados, archivado y operaciones masivas de estado | Matriz completa DF-030, terminal ARCHIVED, padre/variantes, motivos, rollback atómico y auditoría individual/lote. |
| F | Publicación y republicación individual/masiva | Permisos distintos, historia persistente, apagado al salir de ACTIVE, no republicación automática propia, conservación indicadores de variantes, atomicidad. |
| G | Catálogo público | Slug único/inmutable, matriz de visibilidad, padre sin variantes visibles, precio NULL vs cero, lista/búsqueda/directo, lista blanca de datos. |
| H | Suspensión/reactivación, lectura y exportación excepcional | Sesiones previas, admin solo lectura, operador bloqueado, catálogo restaurado automáticamente, autorización de 1–3h por exportación, consumo/caducidad/revocación, datos permitidos exclusivamente. |
| I | Perfiles y concesión de plataforma | Aprovisionamiento real, permisos exactos por perfil, prueba positiva/negativa, no escalada ni ampliación de TENANT_ADMIN, revocación y auditoría. |
| QA | Regresión integral y entrega | Migraciones desde cero y actualización, pruebas unitarias/integración/empaquetado, contratos vigentes, trazabilidad completa, exportación sanitizada. |

Se permite adelantar componentes técnicos estrictamente necesarios de un incremento dependiente (por ejemplo, permisos base para probar una capacidad) sin expandir alcance ni perder la trazabilidad. Registrar el motivo y no duplicar trabajo. Los estados de las fases anteriores no son prueba de que hayan sido aceptadas.

### 11.1 Cierre preciso de A.4

Rutas relativas al producto: GET /prices, GET /prices/tiers, GET /prices/quote, GET /prices/cost, PUT /prices/sale y PUT /prices/cost.

Comprobar OpenAPI servido real: método/ruta/parámetros, request, éxito, errores, media types, HTTP aplicables, tipos, formato, campos requeridos, nulabilidad, restricciones, seguridad Bearer, referencias de esquema resueltas y ausencia de información no autorizada. Contrastar contra expectativas explícitas independientes del documento generado; no limitarse a comprobar existencia de rutas. No inventar contratos.

Mantener evidencia anterior de carreras distintas, venta/costo, rollback auditoría y permisos. No declarar el contrato cerrado por añadir únicamente anotaciones. Actualizar y repetir verificaciones si B–I cambian dichos contratos.

### 11.2 QA transversal obligatorio

- Dos empresas y usuarios con perfiles distintos; comprobar aislamiento de todas las rutas, operaciones masivas, tipos de cambio y exportaciones.
- Probar accesos directos, sesiones previas y permisos revocados, no confiar en ocultar botones ni en claims obsoletos.
- Validar frontera de escalones enteros/decimales (debajo, exactamente, por encima), lista vacía, no agrupación por SKU, precio cero, ausencia de precio de lista, no exponerse costo ni escalones públicamente.
- Validar concurrencia de precio/costo, configuración/umbrales, publicación/estados, suspensión/revocación y consumo de exportaciones según el diseño. Confirmar un único ganador, datos persistidos y auditoría; no solo códigos HTTP.
- Validar rollback por fallo de auditoría, operación masiva con un elemento inválido, cruce de empresas, empresa suspendida y versiones incompatibles. Nunca una actualización parcial no autorizada.
- Verificar todas las transiciones DF-030 (positivas y negativas), archivado sin cascada, ausencia de borrado físico, recuperación del catálogo con indicadores conservados, producto sin variantes visibles y publicación sin stock.
- Pruebas de exportación: sin aprobación, intento por otra persona/empresa, duración <1h o >3h, antes de vigencia, al vencimiento, revocación, doble consumo concurrente, aprobación única, exclusión de cada campo privado y auditoría.
- Validar migraciones nuevas, restricciones, datos anteriores y compatibilidad de API. No alterar V1–V5.

Ejecutar las pruebas focalizadas durante cada incremento; al completar el alcance ejecutar desde el último estado real:

```text
mvn clean test
mvn clean verify "-DskipITs=false"
git diff --check
```

Usar el comando Maven equivalente que ya funcione en el entorno del usuario, respetando las configuraciones de tests. Informar resultados reales: número de tests Surefire y Failsafe, fallos, errores, omitidos, código de salida y evidencia de la última ejecución. Si se modifica código después, repetir la verificación pertinente y regresión final.

Investigar el mensaje conocido de cierre de Failsafe. BUILD SUCCESS por sí solo no demuestra que se resolvió. Si permanece, registrar evidencia, diagnóstico, impacto y siguiente acción; no ocultarlo ni fabricar un conteo.

## 12. Entrega y exportación independiente

Actualizar documentación de precios, API administrativa, seguridad, control de cambios, catálogo público y concesión de perfiles. Registrar por DF/HU/CA la regla, implementación (archivo/migración/contrato), prueba, resultado actual, pendiente y estado de aceptación. Usar nombres y convenciones reales del repositorio.

Al finalizar, generar una exportación integral del estado actual del repositorio (no confundir con el compendio anterior a cambios). Utilizar commercecore-audit-export si está instalada; si no, proceder con la alternativa sin detener la tarea:

- Un único Markdown sanitizado con fecha/corte, rama/HEAD, estado Git, árbol/manifiesto, archivos completos y separadores por ruta, evidencia de pruebas y exclusiones.
- Incluir pom.xml, archivos properties/configuración, src/main y src/test completos, migraciones Flyway, archivos relevantes de Docker/CI, .gitignore, AGENTS.md y documentación/backlog/ADR/contratos. Incluir cambios no confirmados y archivos nuevos relevantes.
- Excluir .git, .idea, .mvn según alcance solicitado, target, binarios, archivos temporales, caches, compendios anteriores, credenciales y datos sensibles reales. Mantener placeholders de configuración y declarar exclusiones/redacciones. No copiar claves/tokens/passwords reales. Evitar que el compendio se incluya a sí mismo.
- No alterar el código para producir la exportación, ni agregar commits. Informar ruta del archivo, número de archivos y advertencias de cobertura. Etiquetar como parcial si existen pendientes; nunca presentar una instantánea parcial como aceptación definitiva.

## 13. Criterio global de finalización

El objetivo se considera técnicamente completo únicamente si:

1. Todas las reglas DF-006 a DF-036 y DF-025A aplicables al backend actual están implementadas o se ha respetado explícitamente su alcance diferido aprobado (inventarios y demás no objetivos).
2. Cada incremento A.4–I tiene evidencia de verificación y trazabilidad; los contratos vigentes se corresponden con la API real.
3. Regresión final ejecutada después del último cambio, sin fallos ni omisiones no justificadas.
4. Las restricciones de seguridad, auditoría, aislamiento, migraciones, privacidad y no borrado físico están verificadas.
5. El compendio integral sanitizado está disponible para QA independiente.

Si hay un bloqueo real, informar alcance exacto, evidencia, intento realizado, impacto y decisión necesaria. Continuar tareas independientes mientras sea seguro. No usar un informe parcial como sustituto de desarrollo y verificación.

Formato de entrega: estado COMPLETADO / INCOMPLETO / BLOQUEADO, incrementos y DF, archivos y migraciones cambiados, pruebas reales, incidencias/riesgos, enlaces o rutas de documentación y compendio, confirmación de restricciones, siguiente paso. La aceptación técnica y funcional independiente corresponde a Miguel Díaz y al PO/QA, no a Codex.
