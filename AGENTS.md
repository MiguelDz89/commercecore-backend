## Política obligatoria de desarrollo CommerceCore

1. No iniciar desarrollo de una HU sin verificar su
   Definition of Ready (DoR).

2. Si existen decisiones funcionales pendientes,
   contradicciones o dependencias bloqueantes, informar
   al líder técnico antes de implementar.

3. Analizar la subdivisión de cada HU en hitos
   pequeños, coherentes y verificables.

4. Identificar módulos afectados y limitar la
   exploración del código al alcance necesario.

5. Cada hito debe tener criterios de aceptación,
   pruebas focalizadas y evidencia verificable.

6. No repetir implementaciones ni pruebas anteriores
   que no hayan sido afectadas.

7. Registrar el avance y las decisiones en el plan
   persistente y la matriz de trazabilidad.

8. Diferenciar implementación, verificación técnica
   y aceptación de QA independiente.

9. No ampliar el alcance ni tomar decisiones de
   negocio no autorizadas.

10. Mantener las restricciones existentes de commits,
    push, despliegues y migraciones.

## CC-ORCHESTRATOR-V1.0

Miguel Díaz es arquitecto y Tech Lead y aprueba las decisiones técnicas.
El Product Owner aprueba los requisitos funcionales. Codex ejecuta el
trabajo de desarrollo autorizado.

Antes de desarrollar, comprobar requisitos aprobados, criterios verificables,
casos positivos y negativos, seguridad, dependencias, decisiones de arquitectura,
alcance e hitos. Si una contradicción o decisión pendiente bloquea el alcance
afectado, informar al líder técnico y solicitar su decisión. No inventar reglas.

Analizar las historias de complejidad media o alta y definir por hito su
identificador, objetivo, alcance, dependencias, agente responsable, criterios,
pruebas y punto de control. Mantener juntas las operaciones que requieran
una misma transacción.

Delegar solo cuando el usuario lo solicite o autorice expresamente. Seleccionar
el agente según el riesgo y ejecutar secuencialmente las etapas dependientes.
No modificar simultáneamente los mismos archivos ni delegar recursivamente
sin autorización. Transferir el contexto necesario y esperar el resultado
antes de iniciar una etapa dependiente. Si un modelo solicitado no está
disponible, informar y solicitar autorización antes de sustituirlo. Astra
requiere autorización explícita.

Preferir tareas acotadas y reutilizar evidencias vigentes. Evitar búsquedas
innecesarias, pruebas repetidas y subagentes para tareas simples. Escalar
capacidad según riesgo y detener reintentos improductivos.

Un hito está verificado cuando cumple sus criterios, ejecuta las pruebas
requeridas sin fallos, errores ni omisiones imprevistas, supera
`git diff --check`, preserva seguridad y aislamiento, y registra evidencias,
trazabilidad y limitaciones. La verificación focalizada no certifica producción.

Preservar cambios preexistentes y migraciones aplicadas. Usar PostgreSQL y
Keycloak temporales sin acceder a bases persistentes. No exponer secretos,
realizar commits, push o despliegues, iniciar incrementos sin autorización,
otorgar permisos implícitos ni modificar requisitos aprobados. Las restricciones
de solo lectura de los agentes son instrucciones de comportamiento y dependen
de los controles de permisos del cliente.
