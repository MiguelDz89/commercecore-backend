# CC-PROC-001 · Deshabilitación excepcional de cantidades fraccionadas

Este procedimiento aplica al fraccionamiento de una empresa después de su alta. La configuración ordinaria de empresa permanece fija. El superadministrador autoriza y ejecuta el cambio excepcional.

1. El administrador empresarial presenta la solicitud, el motivo y la modalidad solicitada. Registrar la empresa, identidad del solicitante y fecha.
2. Plataforma analiza el impacto. Identifica y cuenta productos y variantes con configuración fraccionada o escalones incompatibles, sus unidades y las referencias necesarias para comprobar las correcciones. El análisis conserva los importes, cantidades e históricos originales.
3. Plataforma genera un acta con identificación y referencia, empresa, solicitante, fecha, motivo, modalidades anterior y nueva, número y relación de afectados, criterio de corrección, compromiso del cliente y responsable de corregir. La lista identifica el producto afectado y el motivo de incompatibilidad.
4. El cliente acepta el compromiso. Los usuarios autorizados corrigen configuraciones y escalones mediante las operaciones auditadas habituales. Una corrección de configuración vigente preserva los registros históricos.
5. Plataforma vuelve a verificar todos los productos y variantes de la empresa antes de deshabilitar. Si quedan incompatibilidades, rechaza la operación y devuelve la relación de afectados y motivos solo a los actores autorizados.
6. Un superadministrador autoriza y ejecuta el cambio vinculado al acta. La verificación decisiva, el cambio, el registro y la auditoría se realizan en la misma transacción. El bloqueo por empresa se comparte con las escrituras que puedan introducir incompatibilidades, de modo que ninguna se intercale entre la verificación y la confirmación.
7. Registrar el cierre y conservar el análisis, referencia al acta, resultado, actor, fecha y hora y valores anterior y posterior. Mantener trazabilidad de los rechazos de operación con la evidencia del análisis, sin realizar cambios parciales.

La facturación de este servicio queda fuera del MVP. Las modificaciones de unidad con precios, costes, escalones o historial requieren su propio análisis y aprobación. La conversión de cantidades o importes requiere una regla expresa para el caso afectado.

## Registro técnico propuesto para confirmación

Usar un registro de control de cambios por empresa con referencia única al acta, identidad del solicitante, fechas, motivo, dirección del cambio, criterios, compromiso y responsable de corrección. Asociar el recuento y las referencias de afectados del análisis. Al ejecutar, guardar actor de plataforma, resultado y referencias de auditoría. El acta permanece bajo custodia del proceso administrativo y la aplicación guarda los datos mínimos para correlacionar y validar el cambio. La ejecución no confía en un recuento aportado por el solicitante: vuelve a calcular las incompatibilidades sobre la base vigente.

Confirmación pendiente del Líder Técnico según la sección 4.3 del maestro. La habilitación inicial, unidad base y validación de cantidades se pueden implementar independientemente.
