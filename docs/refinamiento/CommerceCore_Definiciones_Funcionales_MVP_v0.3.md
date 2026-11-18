# CommerceCore — Consolidado funcional del MVP

**Documento:** CC-DOC-001/002-BASE-v0.3 · **Fecha de corte:** 17/11/2026 (Perú)  
**Responsable técnico y arquitecto:** Miguel Ángel Díaz Arica  
**Responsable de refinamiento:** Product Owner / QA-documentador  
**Estado:** línea base funcional consolidada; contraste con código, migraciones y pruebas pendiente.  

> Propósito: entregar al chat de desarrollo backend una única fuente de contraste de las decisiones de negocio trabajadas hasta el momento. Las reglas aprobadas prevalecen sobre borradores anteriores. Este documento no certifica la implementación ni autoriza cambios de arquitectura, commits, push o despliegues. Los puntos marcados como propuesta o pendientes deben resolverse antes de implementar su detalle.

## 1. Visión y alcance

CommerceCore es una plataforma SaaS multiempresa para gestionar y publicar catálogos comerciales, con evolución posterior hacia un ERP y comercio electrónico transaccional. Las primeras referencias son Bellas Sorpresas (regalos, arreglos y productos personalizados) y Motorepuestos Díaz (repuestos, kits y compatibilidad vehicular); la solución debe admitir otras empresas sin acoplarse a estos dominios.

**Objetivo del MVP:** que una empresa autorizada gestione usuarios, permisos, categorías, productos, variantes, atributos, precios, composiciones, imágenes y compatibilidad; importe datos mediante Excel y publique un catálogo público de consulta independiente, con aislamiento y auditoría verificables.

### 1.1 Alcance vigente

| ID | Módulo | Alcance funcional |
|---|---|---|
| MVP-01 | Empresas | Alta, consulta, modificación controlada, límites, suspensión y reactivación. |
| MVP-02 | Identidad | Cuentas, membresías, autenticación y recuperación. |
| MVP-03 | Seguridad | Roles y permisos por tenant; autorización de plataforma, MFA administrativo. |
| MVP-04 | Categorías | Clasificación del catálogo. |
| MVP-05 | Productos | Productos simples y compuestos, estados y consultas. |
| MVP-06 | Atributos | Características configurables y tipadas. |
| MVP-07 | Composiciones | BOM versionada; modalidades COMPONENT_PICK, PREASSEMBLED, MAKE_TO_ORDER. |
| MVP-08 | Multimedia | Imágenes de productos y variantes. |
| MVP-09 | Compatibilidad | Modelos de vehículos y asociaciones opcionales con productos. |
| MVP-10 | Importación | Excel: previsualización, validación y confirmación. |
| MVP-11 | Auditoría | Registro de operaciones de tenant y plataforma, inmutabilidad, retención y recuperación. |
| MVP-12 | Precios | Compra, lista/minorista y escalones mayoristas configurables. |
| MVP-13 | Variantes | SKU, precios, estados y publicación independientes. |
| MVP-14 | Catálogo público | Publicación y consulta de productos elegibles por empresa, sin compras. |

**Fuera del MVP:** proveedores, compras y recepciones transaccionales, inventario físico, almacenes, movimientos de stock, pedidos, carrito, facturación, pagos en línea/pasarelas, acceso de soporte a datos privados, suplantación de usuarios, modo presentación, exportación administrativa de empresas a Excel/CSV y adquisición de llaves físicas de seguridad. La regularización de una deuda de servicio es una condición administrativa, no autorización para implementar un módulo de facturación o pagos. Los mecanismos de archivo y recuperación de auditoría tienen requisitos aprobados; su automatización y cobro deben refinarse sin ampliar implícitamente el alcance transaccional.

## 2. Actores, límites y responsabilidades

| Actor | Competencia | Restricciones |
|---|---|---|
| Superadministrador operativo | Administración de plataforma y recuperación de administración empresarial. | No accede a datos comerciales privados de ninguna empresa por su privilegio de plataforma. |
| Superadministrador de emergencia | Contingencia y recuperación de plataforma. | Uso exclusivamente de emergencia, credenciales/recuperación independientes; mismas restricciones comerciales. |
| Administrador de empresa | Administrar su tenant, usuarios y permisos delegables. | No crea empresas ni concede privilegios superiores a los propios; no opera otros tenants sin membresía autorizada. |
| Gestor de catálogo | Gestionar catálogo según permisos. | Solo su contexto autorizado; perfil inicial propuesto. |
| Usuario de consulta | Consultar según permisos. | Sin privilegios de modificación por defecto; perfil inicial propuesto. |
| Cliente público | Consultar catálogo publicado. | Solo datos explícitamente públicos, no costos, escalones ni información administrativa. |

La identidad de la cuenta y las membresías son conceptos distintos: una persona puede pertenecer a varios tenants, pero ninguna pertenencia se deriva del DNI/RUC compartido. El rol de plataforma no es un rol empresarial y no concede acceso implícito a datos de negocio.

## 3. EP-01 — Empresas y multi-tenancy

**Estado de refinamiento:** reglas principales aprobadas; quedan detalles operativos identificados en la sección 10. **Estado de entrega:** no certificado por QA. Prioridad P0.

### 3.1 Alta e identificación (HU-002)

| Regla | Definición | Estado |
|---|---|---|
| RN-TEN-001 | Se admiten empresas formalizadas y emprendimientos sin RUC. Si hay RUC se registra; en caso contrario se exige DNI del titular. No se admite alta sin identificación válida. | Aprobada |
| RN-TEN-002 | Un DNI puede asociarse a varios emprendimientos; cada uno tendrá un tenant independiente. | Aprobada |
| RN-TEN-003 | Un RUC puede asociarse a varios tenants/marcas. DNI/RUC no son claves únicas del tenant. | Aprobada |
| RN-TEN-004 | El superadministrador asigna un administrador inicial obligatorio. Ninguna empresa activa puede existir sin al menos un administrador activo. | Aprobada |
| RN-TEN-005 | Un administrador puede crear otros administradores de su empresa y otorgar permisos iguales o inferiores a los propios, nunca superiores ni de plataforma. | Aprobada |
| RN-TEN-006 | El superadministrador configura un máximo de administradores por tenant, como mínimo uno. No se admiten nuevas altas que excedan el límite; los cambios se auditan. | Aprobada |
| RN-TEN-007 | El superadministrador puede reducir el máximo aunque haya administradores excedentes. Existe un plazo de gracia de 24 horas antes de la revocación; se audita y se emiten alertas. | Aprobada; procedimiento al vencimiento pendiente |

**Regla transversal:** solo el superadministrador de plataforma puede crear tenants; no hay registro público de empresas. Una empresa activa nunca queda sin administrador activo, incluso después de una revocación, bloqueo de cuenta o cambio de membresía. La recuperación administrativa no debe violar este invariante.

**Datos de alta propuestos (no todos validados todavía):** razón social, nombre comercial, tipo/número de documento, país, moneda principal y correo de contacto obligatorios; teléfono, dirección, logo opcionales. Para un emprendimiento sin razón social formal, falta acordar cómo registrar el nombre legal del titular. El formato, validación y unicidad del identificador público/slug de tenant son pendientes. No debe aplicarse unicidad global a DNI/RUC.

### 3.2 Consulta y mantenimiento (HU-003)

- El superadministrador podrá consultar y administrar datos propios de la plataforma: identificación, estado, límites, administradores y demás metadatos de gestión autorizados. No podrá consultar el catálogo privado, costos ni operaciones comerciales del tenant.
- Los listados mostrarán datos mínimos; no se mostrarán RUC/DNI completos en el listado. Los identificadores, correos y nombres de usuario se enmascararán según necesidad. El detalle mostrará únicamente información necesaria para el rol autorizado. Se debe cerrar el patrón exacto de enmascaramiento y los campos sensibles permitidos.
- El administrador de empresa podrá modificar nombre comercial, logo, teléfono y dirección de su propia empresa.
- Cambiar razón social, DNI o RUC requiere aprobación del superadministrador y auditoría. El flujo de solicitud/aprobación y estados se detallarán en refinamiento técnico-funcional.
- No habrá exportación administrativa de la lista de empresas a Excel o CSV en el MVP. El modo presentación queda para una fase posterior; para demos se recomienda entorno independiente con datos ficticios.

### 3.3 Ciclo de vida: suspensión y reactivación (HU-023, HU-023A, HU-024)

**RN-TEN-008 — No eliminar:** únicamente suspender/reactivar desde la aplicación; preservar datos, configuración e historial. No exponer eliminación definitiva de tenants.

**RN-TEN-009 — Motivos de suspensión:** elegir motivo obligatorio de un catálogo predefinido. Incluye falta de pago, solicitud del titular, incumplimiento contractual, incumplimiento de condiciones de uso, incidente de seguridad y otros. Para OTROS, descripción obligatoria no vacía ni compuesta solo por espacios. Para incumplimiento contractual, documento de sustento obligatorio (carta u otro documento). En los demás motivos el sustento no se declaró obligatorio. Registrar motivo, detalle, documento y cambio de estado con acceso restringido; nunca publicar la causa.

**Dos modalidades de suspensión:** se distinguen por reglas de acceso, no se impone un modelo físico de estados.

| Capacidad | Empresa activa | Suspensión ordinaria | Suspensión grave/seguridad |
|---|---|---|---|
| Administrador de empresa: lectura autorizada | Sí | Sí, solo lectura | No |
| Administrador: escrituras | Según permisos | No | No |
| Usuarios operativos | Según permisos | No | No |
| API privada del tenant | Según permisos | Solo lectura autorizada del administrador | Bloqueo total de acceso empresarial |
| Catálogo público | Según elegibilidad | No | No |
| Datos y banderas de producto | Conservados | Intactos | Intactos |
| Gestión de plataforma autorizada | Sí | Sí | Sí |

La suspensión grave es excepcional y procede únicamente por motivos graves predefinidos: incidente de seguridad confirmado (p. ej., compromiso de credenciales o fuga), orden legal válida o incumplimiento contractual grave documentado. No admite OTROS. Requiere reautenticación WebAuthn del superadministrador, motivo, auditoría y alerta. Bloquea a todos los usuarios empresariales, incluido su administrador. No se debe confundir con la suspensión ordinaria, donde el administrador conserva solo lectura. La validación legal/de evidencia corresponde al proceso administrativo; no se presupone automatización.

**Reactivación — requisitos aprobados:**

1. Únicamente el superadministrador puede reactivar.
2. Debe existir al menos un administrador activo antes de activar la empresa.
3. Si el motivo de suspensión fue falta de pago, la deuda debe haberse regularizado primero. La forma de constatarlo queda pendiente; no implementar pagos en línea por inferencia.
4. Seleccionar un motivo de reactivación predefinido o OTROS con texto obligatorio no vacío; motivos exactos de reactivación por definir.
5. Reautenticación WebAuthn obligatoria inmediatamente antes de ejecutar la reactivación.
6. Registrar transición, actor, motivo y resultado en auditoría.

**DF-016 — Catálogo durante suspensión:** bloqueo global e inmediato del catálogo público; mostrar únicamente mensaje neutro «Catálogo no disponible», sin datos de empresa ni causa. No cambiar estado, indicador de publicación ni versión comercial de cada producto/variante. Al reactivar, recuperar automáticamente la visibilidad de los registros elegibles según sus estados y banderas originales; no exigir republicación individual ni generar miles de operaciones de producto. Auditar la transición de empresa. Ejemplo: 1.000 productos, 200 inactivos, 300 activos no publicados, 500 activos publicados: vuelven a ser visibles únicamente los 500 elegibles, sujeto a reglas de variantes.

**Aislamiento aprobado:** en toda API privada dependiente de tenant, un usuario autenticado de A que intenta operar sobre B sin membresía/permisos obtiene 403 o equivalente sin datos ni metadatos de B. Probar manipulación de tenantId y de identificadores anidados, no solo endpoints de productos. Las sesiones y tokens existentes no deben eludir una suspensión o revocación. Las otras empresas continúan operando.

### 3.4 Reducción del máximo de administradores

Ejemplo acordado: de cinco administradores a tres. El cambio se permite y se concede un período de gracia de 24 horas antes de retirar privilegios excedentes. No debe darse de baja una cuenta global ni perder su historial por editar una membresía. Durante el período se debe impedir aumentar el excedente. **Pendiente de aprobación:** quién selecciona a los administradores excedentes, si puede reordenarse la selección durante el plazo, aviso antes del vencimiento, política si nadie elige y comportamiento cuando se modifica el límite nuevamente. No aplicar revocación arbitraria ni dejar el tenant sin administración. La selección explícita por el superadministrador fue propuesta, no confirmada de forma separada.

## 4. EP-02 — Identidad, seguridad, roles y permisos (refinamiento parcial adelantado)

### 4.1 Reglas aprobadas para plataforma

| Regla | Definición |
|---|---|
| RN-SEG-001 | Un superadministrador operativo. |
| RN-SEG-002 | Un superadministrador de emergencia exclusivamente para contingencias. |
| RN-SEG-003 | Credenciales y mecanismos de recuperación independientes, almacenados de forma segura. |
| RN-SEG-004 | Alertas de seguridad ante ingreso del superadministrador y cambio de contraseña; eventos adicionales de alto riesgo propuestos. |
| RN-SEG-005 | Recuperación administrativa de una empresa por el superadministrador, con reautenticación WebAuthn y motivo obligatorio auditado. |
| RN-SEG-006 | El superadministrador administra la plataforma sin acceso privilegiado a datos comerciales privados de tenants. |
| RN-SEG-007 | Acceso temporal de soporte y suplantación excluidos del MVP. |

**Autenticación aprobada:** Keycloak, usuario + contraseña + WebAuthn obligatorio para ambas cuentas de superadministrador. Se posponen dispositivos físicos FIDO2; usar autenticadores de plataforma compatibles (p. ej., biometría/PIN del dispositivo). No se sustituye WebAuthn por SMS, correo o métodos más débiles como alternativa automática; configuración exacta y recuperación segura deben diseñarse y probarse. La cuenta de administrador de infraestructura/Keycloak es distinta del superadministrador de CommerceCore (recomendación técnica). La aplicación no debe confiar solo en el rol: la exigencia de MFA y la reautenticación deben poder verificarse en backend. El privilegio PLATFORM_SUPER_ADMIN procede de OIDC/Keycloak, no de RBAC de empresa; asignación no pública. Identity gestiona cuentas, membresías y permisos de negocio.

**Reautenticación:** obligatoria para recuperación administrativa, suspensión grave y reactivación. Se recomienda evaluar otras operaciones de alto impacto (cambio de límites y credenciales), sin declararlas aprobadas hasta su refinamiento.

**Alertas:** mínimo acordado, cada inicio de sesión exitoso de superadministrador y cambio de contraseña. El acceso de emergencia debe distinguirse y alertarse como crítico. Se propusieron además intentos fallidos repetidos, restablecimiento de contraseña, cambios/eliminación de MFA y cambios de roles, como eventos de severidad alta/crítica. Confirmar lista final y canal; Telegram y correo quedaron como propuesta, no como selección aprobada. No enviar secretos, contraseñas, tokens ni datos comerciales privados a canales de alertas. Registro de seguridad independiente del envío y fallos de entrega no deben invalidar ni silenciar la auditoría.

### 4.2 Matriz funcional de privilegios (resumen)

| Operación | Superadministrador | Administrador tenant | Usuario operativo | Público |
|---|---|---|---|---|
| Crear tenant | Sí | No | No | No |
| Suspender/reactivar tenant | Sí | No | No | No |
| Configurar máximo admins | Sí | No | No | No |
| Recuperar administrador | Sí | Delegación normal propia | No | No |
| Aprobar modificación DNI/RUC/razón social | Sí | Solicita | No | No |
| Gestionar usuarios/permisos de tenant | Solo funciones de plataforma de recuperación autorizadas | Según permisos delegables propios | Según permiso específico si aplica | No |
| Consultar datos comerciales privados | No por rol de plataforma | Según permisos y membresía | Según permisos y membresía | No |
| Consultar costos | No por rol de plataforma | Solo permiso específico | Solo permiso específico | No |
| Publicar/republicar productos | No por rol de plataforma | Según permisos | Según permisos | No |
| Consultar catálogo público | Como cualquier visitante, solo datos públicos | Sí | Sí | Sí |

**Pendientes principales de EP-02:** invitación y activación de usuarios; verificación de correo, cambio/restablecimiento de contraseña, sesiones y revocación de tokens, política de MFA de administradores y usuarios empresariales, catálogo formal de permisos y roles iniciales, mecanismo de contingencia y pruebas, alertas concretas y canales. No confundir un rol de plataforma con acceso a una membresía comercial ni permitir que el superadministrador se autoasigne membresías para eludir la prohibición.

## 5. Catálogo, productos y variantes

### 5.1 Modelo funcional

- Categorías, atributos configurables tipados, productos SIMPLE y COMPOSITE, BOM versionada y modalidades COMPONENT_PICK, PREASSEMBLED y MAKE_TO_ORDER.
- Variantes de productos: por ejemplo, una caja de regalo pequeña, mediana y grande. Cada variante tiene su propio SKU, precio de lista, escalones mayoristas y estado/indicador de publicación. La estructura exacta de atributos de variación, imágenes por variante y unicidad de SKU requiere refinamiento.
- Los futuros movimientos de inventario deberán poder identificar variante individual, pero **el inventario no entra en el MVP**. No bloquear publicación por desconocer existencias ni inferir stock de los estados.
- Compatibilidad vehicular opcional mediante modelos de vehículo y asociaciones producto–vehículo. El catálogo no se limita a repuestos.

### 5.2 Estados y publicación (DF-010 a DF-014)

Estados funcionales: **Pendiente de recepción** (solicitado a proveedor, todavía no recibido; registro interno), **Activo** (habilitado para comercializar) e **Inactivo** (deshabilitado). Nombres técnicos definitivos y transiciones permitidas se deberán alinear al modelo existente. El estado no constituye gestión de compras ni inventario; se administra manualmente en el MVP.

- Todo producto y toda variante posee indicador de publicación independiente, visible en el mantenimiento de catálogo.
- Solo un registro activo puede habilitar su indicador de publicación. Al pasar un producto a inactivo, desactivar automáticamente su indicador. La misma restricción de elegibilidad se aplica a variantes; precisar el efecto físico sobre las banderas de las variantes cuando se inactiva el padre, sin perder decisiones independientes por inferencia.
- Reactivar un producto no enciende automáticamente su indicador. Publicar de nuevo exige permiso especial (nombre propuesto PRODUCT_REPUBLISH), ya sea tras inactivación o tras ocultamiento voluntario, y auditoría.
- Ocultar un producto del catálogo no implica inactivarlo; puede permanecer activo internamente.
- Para mostrar una variante, tanto el producto padre como la variante deben estar activos y tener su indicador de publicación habilitado, además de que la empresa esté activa. La suspensión de empresa es una restricción superior, no una modificación de los flags individuales.
- La primera publicación, retirada y modificación de estado son acciones diferenciadas; los nombres de permisos PRODUCT_PUBLISH, PRODUCT_UNPUBLISH, PRODUCT_STATUS_UPDATE y PRODUCT_REPUBLISH son propuestas para mapear al código existente.

| Estado empresa | Producto activo y publicado | Variante activa y publicada | Visibilidad variante |
|---|---|---|---|
| Activa | Sí | Sí | Visible |
| Activa | Sí | No | Oculta |
| Activa | No | Sí | Oculta |
| Suspendida (cualquiera) | Sí | Sí | Oculta |

Un producto sin variantes es visible si la empresa está activa y él está activo/publicado. Falta definir la presentación de un padre con variantes pero sin ninguna variante elegible (detalles de UX y reglas de listado).

### 5.3 Precios (DF-001, DF-006, DF-007, DF-008)

- Precio de compra/costo, precio de venta de lista (minorista), y precio mayorista configurable mediante escalones por cantidad.
- No existe un número fijo de escalones para todos: un producto puede tener tres y otro cinco; cada variante tiene sus propios escalones. El máximo de escalones y si se admite no configurar ninguno deben precisarse. No inventar un escalón por defecto.
- Cada escalón se activa cuando la cantidad solicitada es **mayor o igual** a su umbral. Seleccionar el umbral más alto alcanzado. Se aplica el precio de dicho escalón a todas las unidades, no de forma marginal/progresiva.
- Ejemplo: lista S/ 100, desde 5 unidades S/ 90, desde 10 unidades S/ 80.

| Cantidad | Unitario | Total |
|---:|---:|---:|
| 1 | 100 | 100 |
| 4 | 100 | 400 |
| 5 | 90 | 450 |
| 9 | 90 | 810 |
| 10 | 80 | 800 |
| 12 | 80 | 960 |

El salto de total entre 9 y 10 unidades es consecuencia directa de la regla aprobada. Los importes son ilustrativos; el cambio de precio se debe validar con exactitud decimal y política monetaria existente.

- El catálogo público expone únicamente precio de lista, nunca costo de compra ni escalones mayoristas. El costo requiere permisos específicos de lectura y modificación; la autorización debe cubrir DTO, importación, exportaciones que existan, logs, auditoría y snapshots, no solo las pantallas.
- CODEX-013D.2 propuso BigDecimal, hasta dos decimales, cantidades mínimas de escalón >=2, umbrales únicos y orden no creciente de precios, concurrencia/versionado y auditoría. Son propuestas técnicas que deben verificarse con las reglas aprobadas; resolver expresamente descuentos no monótonos, montos cero/moneda, visibilidad de escalones dentro del tenant y reglas cuando las variantes existen.

### 5.4 Catálogo público, imágenes e importación

- Cada empresa tiene un catálogo público independiente para mostrar los productos a clientes sin proceso de compra. No se necesitan existencias conocidas para publicar. La consulta sin autenticación y búsqueda/filtros/detalle con imágenes se consideran backlog de catálogo; canal y URL públicos requieren diseño.
- No exponer costos, condiciones mayoristas, documentos de suspensión, datos de usuarios, auditoría ni información comercial privada. El material no publicado no debe quedar accesible mediante URL directa, búsqueda, caché o API secundaria. El diseño del almacenamiento de imágenes debe respetar elegibilidad y bloqueo global, evitando confiar solo en ocultar la interfaz.
- Multimedia con Cloudflare R2 (S3 compatible), almacenamiento de referencias en PostgreSQL; pendiente la política exacta de imágenes por variante, tamaño/tipo, control de acceso y eliminación.
- La importación Excel contempla carga, previsualización, validación y confirmación. Debe aplicar las mismas reglas de tenant, costo, estados, variantes y publicación que la creación manual. Pendiente formato de plantilla, estrategia de duplicados/errores, permisos, atomicidad y si puede publicar directamente o requiere operación posterior; no permitir bypass de permiso de republicación.

## 6. EP-08 — Auditoría (transversal)

### 6.1 Datos mínimos aprobados

| Campo | Definición |
|---|---|
| Fecha y hora | Instante de la operación. |
| Actor | Identificación inequívoca de quien ejecutó la acción. |
| Acción | Operación realizada. |
| Aplicación/módulo de origen | Módulo o pantalla desde la cual se hizo; validar origen confiable, no confiar exclusivamente en dato enviado por cliente. |
| Tenant afectado | Empresa cuando corresponda; eventos globales pueden no tener tenant. |
| Motivo | Requerido en operaciones que lo exigen. |
| IP | Si está disponible y puede obtenerse de forma confiable. |
| Resultado | Éxito o fallo. |

Se registrarán las operaciones administrativas de plataforma (alta, modificación sensible/aprobación, límites, delegación/revocación, bloqueo, suspensión/reactivación, recuperación de administración y recuperación histórica) y las acciones comerciales relevantes según permisos y reglas de negocio. Registrar estados anterior/nuevo cuando corresponda; conservar referencia segura a documento de sustento. No guardar contraseñas, tokens ni información comercial sensible en auditoría global. La auditoría de plataforma y de tenant deben conservar separación y autorización; eventos globales sin tenant deben poder representarse sin obligar un tenant ficticio.

### 6.2 Inmutabilidad, retención y archivado

- Los registros de auditoría son inmutables: no se editan ni eliminan mediante la aplicación.
- Retención mínima cinco años. Un año en línea para consultas frecuentes como período inicial configurable; los períodos anteriores pasan a archivo histórico, manteniendo integridad, inmutabilidad y recuperación. No equivale a autorización para eliminar automáticamente después de cinco años; política posterior pendiente.
- El usuario contempla particionamiento temporal y tablas/almacenamiento histórico. La solución física, frecuencia de archivo, verificación de integridad y consultas se definen por arquitectura.
- Recuperación histórica solicitada por empresa puede tener cobro adicional por costos de recuperación. Las solicitudes requieren aprobación del superadministrador, y auditoría de aprobación y entrega. Procedimiento comercial de cotización, pago/constancia, SLA y alcance exacto de interfaz pendientes; no se incorpora pasarela al MVP.
- Consultas internas de seguridad de la plataforma no sujetas a cobro, sin otorgar acceso a información comercial privada.

## 7. Product Backlog consolidado

Prioridades y secuencia internas de referencia: P0 = seguridad/fundacional; P1 = necesario para cierre del MVP. El refinamiento de una historia no acredita implementación. Los identificadores se conservan del backlog trabajado; las ampliaciones sin ID final se señalan como propuestas vinculadas a la historia principal para evitar colisión.

| ID | Historia | Épica | Prioridad | Estado funcional |
|---|---|---|---|---|
| HU-001 | Autenticarse como superadministrador | EP-02 | P0 | Reglas MFA aprobadas |
| HU-002 | Crear empresa | EP-01 | P0 | Refinada; campos detallados pendientes |
| HU-003 | Consultar y administrar empresas | EP-01 | P0 | Refinada; máscaras y flujos de aprobación pendientes |
| HU-004 | Registrar usuarios y membresías | EP-02 | P0 | Parcial |
| HU-005 | Gestionar roles y permisos por empresa | EP-02 | P0 | Parcial |
| HU-006 | Acceder al contexto de empresa autorizada | EP-02 | P0 | Aislamiento aprobado |
| HU-007 | Gestionar categorías | EP-03 | P1 | Pendiente refinamiento |
| HU-008 | Registrar productos simples | EP-03 | P1 | Parcial |
| HU-009 | Consultar/actualizar productos y estados | EP-03 | P1 | Reglas de estados aprobadas; detalles pendientes |
| HU-010 | Configurar atributos de productos | EP-03 | P1 | Pendiente refinamiento |
| HU-011 | Registrar productos compuestos | EP-04 | P1 | Parcial |
| HU-012 | Gestionar composiciones y versiones BOM | EP-04 | P1 | Parcial |
| HU-013 | Configurar modalidades de preparación | EP-04 | P1 | Modalidades definidas |
| HU-014 | Cargar y administrar imágenes | EP-05 | P1 | Parcial |
| HU-015 | Gestionar modelos de vehículos | EP-06 | P1 | Pendiente refinamiento |
| HU-016 | Asociar productos con vehículos compatibles | EP-06 | P1 | Pendiente refinamiento |
| HU-017 | Cargar y previsualizar Excel | EP-07 | P1 | Parcial |
| HU-018 | Validar y confirmar importaciones | EP-07 | P1 | Parcial |
| HU-019 | Registrar operaciones de auditoría | EP-08 | P0 | Campos/retención aprobados |
| HU-020 | Registrar precios de compra, lista y mayoristas | EP-09 | P1 | Reglas principales aprobadas |
| HU-021 | Gestionar variantes | EP-04 | P1 | Parcial; independencia aprobada |
| HU-022 | Configurar publicación de un producto | EP-10 | P1 | Reglas principales aprobadas |
| HU-022A | Publicar u ocultar productos | EP-10 | P1 | Refinada |
| HU-022B | Republicar productos | EP-10 | P1 | Permiso especial y auditoría aprobados |
| HU-023 | Suspender empresa | EP-01 | P0 | Refinada; modalidades diferenciadas |
| HU-023A | Consultar empresa suspendida en solo lectura | EP-01 | P0 | Aprobada para suspensión ordinaria |
| HU-024 | Reactivar empresa | EP-01 | P0 | Refinada; evidencia de pago pendiente |
| HU-025 | Consultar catálogo público de empresa | EP-10 | P1 | Aprobado |
| HU-026 | Buscar y filtrar productos públicos | EP-10 | P1 | Pendiente refinamiento |
| HU-027 | Consultar detalle y variantes publicadas | EP-10 | P1 | Parcial |
| HU-028 | Configurar superadministrador de contingencia | EP-02 | P0 | Aprobada |
| HU-029 | Autenticar superadministradores mediante MFA | EP-02 | P0 | Aprobada: contraseña + WebAuthn |
| HU-030 | Recibir alertas de seguridad | EP-02 | P0 | Aprobada; canal pendiente |
| HU-031 | Recuperar acceso administrativo de forma segura | EP-02 | P0 | Aprobada; flujo técnico pendiente |

Ampliaciones que deben quedar trazadas a HU-003/HU-005/HU-023/HU-024/HU-019: aprobación de cambio de identificación, máximo y reducción de administradores con 24 horas, suspensión grave, consulta administrativa restringida, recuperación de auditoría histórica. Asignar identificadores de subhistorias sin reutilizar los existentes durante el siguiente refinamiento.

### Orden de épicas para refinamiento

EP-01 Empresas y multi-tenancy (cierre de reglas principales); EP-02 Identidad, seguridad, roles y permisos (siguiente); EP-03 Categorías y productos; EP-04 Variantes y productos compuestos; EP-09 Precios; EP-05 Multimedia; EP-06 Compatibilidad vehicular; EP-10 Publicación y catálogo público; EP-07 Importación Excel; EP-08 Auditoría transversal. Este orden documental no sustituye el orden de tareas técnicas AJ ni compromete fechas de sprints.

## 8. Criterios de aceptación y casos de QA prioritarios

Cada fila es una expectativa funcional a ejecutar; **todos se consideran pendientes de verificación contra la revisión real del backend**. No se afirma que el código ya los cumpla.

| ID | HU / regla | Escenario | Resultado esperado |
|---|---|---|---|
| QA-TEN-01 | HU-002 / RN-TEN-001–003 | Alta con DNI sin RUC y dos emprendimientos del mismo titular; alta de marcas del mismo RUC. | Permitido; tenantId distintos, sin acceso cruzado. |
| QA-TEN-02 | HU-002 | Usuario no autenticado o administrador empresarial intenta crear tenant. | Rechazo; sin cambios. |
| QA-TEN-03 | HU-002 / RN-TEN-004 | Crear o activar sin administrador activo; retirar al último administrador. | Rechazo o mecanismo de recuperación que mantenga invariante; nunca tenant activo sin admin. |
| QA-TEN-04 | HU-005 / RN-TEN-005 | Administrador intenta otorgar permiso que no posee o PLATFORM_SUPER_ADMIN. | Denegado; auditabilidad; sin escalamiento. |
| QA-TEN-05 | HU-005 / RN-TEN-006 | Añadir administrador cuando se alcanzó máximo; altas concurrentes. | Sin exceder el límite efectivo. |
| QA-TEN-06 | HU-003 / RN-TEN-007 | Reducir máximo de 5 a 3 con 5 activos. | Cambio permitido; gracia de 24 h, sin nuevas altas excedentes; revocación según procedimiento pendiente; auditoría y aviso. |
| QA-TEN-07 | HU-003 | Admin modifica campos autorizados; solicita cambiar DNI/RUC/razón social. | Campos permitidos actualizados; datos legales solo tras aprobación; trazabilidad. |
| QA-TEN-08 | HU-003 | Listado de empresas y tentativa de exportación. | Datos mínimos/enmascarados; no exportación de lista MVP. |
| QA-TEN-09 | HU-023 | Suspender sin motivo, OTROS vacío o incumplimiento contractual sin documento. | Rechazo sin cambios. |
| QA-TEN-10 | HU-023/023A | Suspensión ordinaria con sesión ya iniciada. | Admin autorizado lee; toda escritura denegada; usuarios operativos bloqueados; catálogo oculto. |
| QA-TEN-11 | HU-023 | Suspensión grave por motivo no permitido o sin reautenticación. | Denegada; no cambios. |
| QA-TEN-12 | HU-023 | Suspensión grave válida con MFA reciente. | Todos los accesos empresariales bloqueados, incluso admin y sesión previa; auditoría y alerta. |
| QA-TEN-13 | HU-023/024 | Suspender y reactivar tenant con 1.000 productos del ejemplo. | Se conserva cada estado/flag; catálogo neutro suspendido; tras reactivar solo 500 elegibles vuelven. |
| QA-TEN-14 | HU-024 | Reactivar sin admin activo, deuda sin regularizar, motivo inválido o sin reautenticación. | Rechazo; tenant sigue suspendido. |
| QA-TEN-15 | HU-024 | Reactivar tras regularización y admin activo, con motivo y WebAuthn. | Empresa activa; recuperación automática de catálogo; evento auditado. |
| QA-TEN-16 | HU-003 | Intento de eliminar definitivamente empresa. | No existe operación autorizada de borrado; datos conservados. |
| QA-SEC-01 | HU-006 | Autenticado A cambia tenantId por B en lectura/escritura/recursos anidados. | 403 o equivalente sin datos/metadatos; sin modificaciones en B. |
| QA-SEC-02 | HU-006 | Usuario pierde permiso/membresía pero conserva token válido. | No puede eludir revocación ni suspensión. |
| QA-SEC-03 | HU-001/028/029 | Superadministrador intenta acceder solo con contraseña. | Acceso privilegiado denegado hasta completar WebAuthn. |
| QA-SEC-04 | HU-030 | Login exitoso y cambio de contraseña del superadministrador; acceso emergencia. | Evento y alerta según condiciones aprobadas; contenido sin secretos. |
| QA-SEC-05 | HU-031 | Pérdida de acceso administrativo de empresa. | Recuperación autorizada, MFA reforzado, motivo y auditoría; no acceso comercial. |
| QA-SEC-06 | RN-SEG-006/007 | Superadministrador llama API privada de productos/costos o intenta suplantar. | Denegado por rol de plataforma; no bypass. |
| QA-PROD-01 | HU-009/021/022 | Intento de publicar registro pendiente/inactivo. | Rechazo. |
| QA-PROD-02 | HU-009/022B | Inactivar producto publicado, reactivar y volver a publicar. | Flag se apaga, no se recupera al activar; republicación requiere permiso y auditoría. |
| QA-PROD-03 | HU-022B | Retirar voluntariamente publicación, mantener activo y republicar. | Estado sigue activo; permiso especial obligatorio al volver a publicar. |
| QA-PROD-04 | HU-021/027 | Producto padre no elegible con variante activa/publicada, y viceversa. | Variante oculta; solo ambos elegibles se muestran. |
| QA-PROD-05 | HU-025 | Acceder a catálogo suspendido por listado, detalle, búsqueda o URL de imagen. | No datos de catálogo ni causa; bloqueo conforme a la estrategia aprobada. |
| QA-PRICE-01 | HU-020 | Consultar cantidad 1/4/5/9/10/12 del ejemplo. | Precios 100/100/90/90/80/80, aplicados a todas las unidades. |
| QA-PRICE-02 | HU-021 | Dos variantes con escalones diferentes. | Cada variante calcula con sus propios valores, sin herencia implícita. |
| QA-PRICE-03 | HU-020/025 | Público o usuario sin PRODUCT_COST_READ (nombre propuesto) solicita costo. | Sin exposición directa, por DTO, reporte o snapshot. |
| QA-IMP-01 | HU-017/018 | Excel inválido, datos de otro tenant o intento de publicación/republicación sin permiso. | Validación y rechazo según política; sin bypass de reglas. |
| QA-AUD-01 | HU-019 | Operaciones administrativas exitosas y rechazadas. | Eventos con campos mínimos, motivo/resultado y referencias correctas; sin secretos. |
| QA-AUD-02 | HU-019 | Intento de editar/borrar evento; traslado a histórico y recuperación. | Inmutabilidad y conservación por cinco años, recuperación autorizada y auditada. |

## 9. Arquitectura de referencia y contraste con Codex

- Stack vigente: Java 21 + Quarkus, monolito modular, Angular, PostgreSQL con tenant_id, Flyway, OIDC/Keycloak, Identity para RBAC empresarial y Cloudflare R2. Neon es candidato PostgreSQL del MVP; proveedor cloud y costos se evalúan en el chat exclusivo de infraestructura, no son decisiones nuevas de esta línea base.
- Contrato de referencia de endpoints privados: `/api/v1/tenants/{tenantId}/products`. Verificar pertenencia y permisos en backend; no confiar en UI, tenantId del path/body ni token por sí solos.
- Antecedentes de desarrollo mencionados en conversaciones: CODEX-010 (Keycloak/Identity), CODEX-012A (onboarding y aislamiento; reportó 83 pruebas Surefire y 3 Failsafe), CODEX-013D.1/V5 (reportó 87 Surefire y 3 Failsafe en PostgreSQL temporal; no aplicación confirmada a commercecore_dev), CODEX-013D.2 (propuesta de precios). Son antecedentes reportados, no evidencia de la última revisión del repositorio. Las nuevas reglas pueden invalidar comportamientos comprobados en pruebas anteriores.

| Ajuste | Alcance | Trazabilidad principal | Estado |
|---|---|---|---|
| AJ-01 | Empresas, límites, seguridad multiempresa y suspensión | HU-002,003,005,006,023,023A,024,031; CODEX-012A | Prioridad P0; prompt previo debe incorporar nuevas reglas |
| AJ-02 | Precios y escalones | HU-020,021; CODEX-013D.2 | Pendiente contraste |
| AJ-03 | Estados de productos y variantes | HU-009,021 | Pendiente contraste |
| AJ-04 | Publicación, republicación, auditoría | HU-022,A/B,019 | Pendiente contraste |
| AJ-05 | Catálogo público | HU-025–027 | Pendiente contraste |
| AJ-06 | Importación Excel | HU-017,018 | Pendiente contraste |
| AJ-07 | Pantallas y pruebas integradas | Todas | Pendiente contraste |
| Nuevo paquete propuesto (sin ID oficial) | Seguridad de cuentas de plataforma, MFA, alertas y recuperación | HU-028–031, HU-001 | P0, planificar con arquitectura |
| Nuevo paquete propuesto (sin ID oficial) | Auditoría global, archivado/recuperación | HU-019 | Separar política aprobada de implementación a planificar |

## 10. Puntos que deben resolverse antes de implementar detalles no aprobados

1. Modelo de datos de tenant: campos exactos obligatorios, razón social de emprendimientos informales, tipo de documento y validaciones, identificador público único, manejo de cambio de titular/identificación y flujo de aprobación.
2. Límite de administradores: selección de excedentes, notificación, procesamiento de vencimiento de 24 h, concurrencia y contingencia; impedir que un activo quede sin admin. Aclarar si administradores pendientes/invitados consumen cuota.
3. Suspensión ordinaria: confirmar si exige WebAuthn de nuevo (aprobada explícitamente para grave y reactivación); representar tipos, motivos, documentos, recuperación de deuda y evidencia administrativa. Definir qué sucede con una empresa suspendida sin administrador y cómo recuperarla sin reactivarla indebidamente.
4. Seguridad: canales de alertas (correo, Telegram, otros), eventos adicionales, destinatario independiente, política de MFA empresarial, recuperación de ambos superadministradores sin bypass inseguro y revocación efectiva de sesiones.
5. Datos personales: especificar máscaras, acceso a detalle, registro de visualizaciones sensibles y visibilidad de los documentos de soporte; no sustituir autorización por enmascaramiento de UI.
6. Catálogo: taxonomía/estados técnicos exactos, transiciones pendiente de recepción, efecto sobre indicadores de variantes al inactivar padre, producto padre sin variantes elegibles, mecanismo confiable para distinguir primera publicación de republicación (incluidos ciclos de ocultamiento), URLs y caché de imágenes.
7. Precios: cardinalidad mínima/máxima de escalones, política de moneda, precisión, validaciones y umbrales, adaptación al modelo de variantes, ocultamiento en auditorías y contratos de API.
8. Importación: plantilla, estrategia de alta/actualización, duplicados, validación por filas, atomicidad y autorización de acciones de publicación/costo.
9. Auditoría: diseño físico de particiones y archivo, ventana configurable, política una vez superados cinco años, recuperación, tarifas y evidencias, seguridad de datos históricos, procedimiento de solicitud sin acceso comercial del superadministrador. No definir purga automática ni pasarela de pago sin aprobación.
10. Definiciones restantes de cada épica y métricas no funcionales (rendimiento, disponibilidad, capacidad, retención de logs operativos independientes de auditoría, costos y usabilidad).

## 11. Instrucción de contraste para el chat backend

> Actúa como líder de revisión técnica de CommerceCore. Lee este documento como línea base funcional más reciente. Contrasta cada regla aprobada y criterio de aceptación con el estado real del repositorio (código, migraciones Flyway, configuración de Keycloak, contratos API, tests y documentación). No implementes ni modifiques nada en esta primera revisión. Identifica incompatibilidades, omisiones y casos no probados, en especial la distinción entre suspensión ordinaria y grave, administrador inicial, límite y plazo de 24 horas, doble autenticación de superadministrador, ausencia de privilegio comercial, auditoría y publicación global. Indica archivo/clase/migración/test y evidencia verificable; separa IMPLEMENTADO, PARCIAL, NO IMPLEMENTADO, CONTRADICE y NO VERIFICADO. No uses el éxito de una prueba antigua como evidencia de una nueva regla. No conviertas propuestas pendientes en requisitos. Sugiere tareas correctivas ordenadas por impacto y dependencias, preservando IDs anteriores y sin commits, push, migraciones en bases compartidas ni despliegues. Espera aprobación del arquitecto antes de modificar el sistema. Devuelve una matriz por ID de regla/HU, evidencia, brecha, severidad, ajuste propuesto y pruebas necesarias.

## 12. Fuentes de trazabilidad documental

Consolidado de decisiones expresas del usuario del chat de QA/PO, incluyendo aprobación por voz; línea base de visión y backlog del 15/11 y refinamientos del 17/11/2026. Documentos antecedentes: CC-ACT-2026-11-17-01 (Acta de refinamiento), CC-DOC-002-REF-01 (Historias, permisos y pantallas), CC-PLAN-AJUSTES-001 (Plan correctivo, AJ-01 v1.1) y Prompts AJ-02 a AJ-07. Cuando una versión anterior no contiene las nuevas reglas del presente documento, utilizar este consolidado para el contraste. No reemplaza los ADR técnicos ni certifica implementación.

**Próximo paso:** validar la matriz de brechas del backend y continuar el refinamiento de EP-02. Mantener separados aprobación funcional, desarrollo, QA y aceptación final.
