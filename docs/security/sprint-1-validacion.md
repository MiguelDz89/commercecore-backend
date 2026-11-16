# Registro de validación del Sprint 1

El recorrido de integración `SprintOnePackagedIT` ejecuta la aplicación
empaquetada con Keycloak y PostgreSQL efímeros, crea dos empresas y prueba
provisionamiento, onboarding, membresías, roles, aislamiento, tokens inválidos
y vencidos. El rol comercial usado procede de `commercialProfiles` durante
el onboarding; no se crea ningún endpoint ni rol comercial por SQL de fixture.

El corte de base pausa exclusivamente el contenedor PostgreSQL de la prueba.
Keycloak sigue emitiendo tokens; se comprueban 503, ausencia de cambios y
recuperación tras restaurar el contenedor. No se accede a `commercecore_dev`.
La consulta paginada de roles agrupa sus permisos en una sola sentencia;
`pagedRoleLoadingUsesOneQueryRegardlessOfPageSize` cuenta las sentencias.

El cierre de Failsafe y la advertencia de logging conservan la incidencia
descrita en `failsafe-windows.md`. Un `BUILD SUCCESS` no demuestra que esté
resuelta. La aceptación requiere revisar los resultados finales de Maven y las
limitaciones de este incremento.

## Resultado de la ejecución del 16 de noviembre de 2026

- `clean test`: 83 pruebas, sin fallos, errores ni omisiones; `BUILD SUCCESS`.
- `clean verify "-DskipITs=false"`: 83 pruebas Surefire y 3 Failsafe, sin
  fallos, errores ni omisiones; `BUILD SUCCESS`.
- El token de vida corta fue rechazado tras su vencimiento. El corte del
  PostgreSQL temporal produjo 503; después de restaurarlo se confirmaron los
  conteos de membresías y auditoría y se pudo crear una invitación.
- En una ejecución anterior, el Keycloak de Dev Services agotó el límite de
  arranque y una prueba histórica observó PostgreSQL aún iniciándose. El perfil
  de test ahora permite hasta tres minutos y la prueba histórica espera la
  señal de disponibilidad definitiva. Ambas suites completas pasaron después.
- No se modificaron V1–V4 ni se conectó la base `commercecore_dev`. Los
  resultados no resuelven por sí solos la incidencia de cierre de Failsafe.
