# CC-ORCHESTRATOR-V1.0

El protocolo organiza el trabajo asistido por IA en hitos verificables para
CommerceCore. Miguel Díaz dirige las decisiones técnicas y el Product Owner
aprueba los requisitos funcionales. Codex ejecuta el trabajo autorizado.
ADR-017, el documento maestro y las instrucciones vigentes del usuario
determinan el alcance.

## Agentes

| Agente | Modelo | Razonamiento | Uso |
|---|---|---|---|
| analyst | gpt-6-luna | medium | Definition of Ready, dependencias y criterios |
| developer | gpt-6.1-terra | medium | Implementación acotada |
| senior_developer | gpt-6.2-sol | high | Transacciones, invariantes y seguridad compleja |
| qa | gpt-6.1-terra | medium | Pruebas funcionales, REST y OpenAPI |
| qa_concurrency | gpt-6.2-sol | high | Concurrencia, rollback y consistencia |
| security_reviewer | gpt-6.2-sol | high | Revisión OIDC, RBAC y aislamiento |
| documenter | gpt-6-luna | medium | Documentación y evidencias |

La configuración establece un máximo de un subagente abierto a la vez y deja
libre la elección del modelo principal. La disponibilidad efectiva de cada
modelo depende de la cuenta, cliente y proveedor. Las instrucciones de solo
lectura no crean un sandbox independiente y los agentes heredan los permisos
del proceso principal.

## Flujo y criterios

1. Confirmar la autorización del usuario para delegar. Leer el punto de control,
   las instrucciones y los archivos pertinentes, y conservar los cambios previos.
2. Comprobar Definition of Ready. Deben existir requisitos aprobados, criterios
   verificables, casos positivos y negativos, seguridad, dependencias, decisiones
   de arquitectura, alcance e hitos. Comunicar al líder técnico las decisiones
   que bloqueen el trabajo afectado.
3. Para cada hito de complejidad media o alta registrar identificador, objetivo,
   alcance, dependencias, agente responsable, criterios, pruebas y punto de
   control. Mantener las operaciones atómicas dentro de una misma transacción.
4. Seleccionar el agente según complejidad y riesgo. Transferir únicamente el
   contexto necesario. Ejecutar etapas dependientes secuencialmente, sin
   escrituras concurrentes sobre los mismos archivos ni delegación recursiva
   sin autorización.
5. Recibir el resultado, revisar los cambios y resolver defectos dentro del
   alcance autorizado. Si el modelo requerido no está disponible, informar y
   solicitar autorización antes de sustituirlo. Astra requiere autorización
   explícita.
6. Definition of Done exige criterios satisfechos, pruebas requeridas ejecutadas
   sin fallos ni omisiones imprevistas, `git diff --check` sin errores,
   seguridad y aislamiento preservados, evidencia, trazabilidad y limitaciones.
   La aceptación independiente corresponde al líder técnico y QA.

El plan persistente y la matriz de trazabilidad deben registrar el corte, los
archivos modificados, comandos, fechas, pruebas, resultados y limitaciones.
Ante un bloqueo, registrar la causa, lo intentado, el impacto y la primera
acción pendiente. Al reanudar, contrastar ese punto con Git y la evidencia
existente antes de editar. Evitar repetir pruebas vigentes y detener reintentos
sin información nueva.

## Piloto propuesto

Con autorización expresa, el orquestador seleccionado como gpt-6.2-sol con
razonamiento medium asignará E.4.2 a `qa_concurrency`. Al recibir y revisar
el resultado, asignará E.5 a `documenter` con la evidencia pertinente. Esta
secuencia queda como ejemplo y requiere las instrucciones y autorizaciones
correspondientes a cada hito.

Ejemplo de delegación futura:

> Usa qa_concurrency para el alcance aprobado de E.4.2. Ejecuta las pruebas
> focalizadas indicadas y entrega comandos, resultados y archivos modificados.
> Espera el resultado antes de asignar documentación.

Ejemplo posterior:

> Usa documenter para actualizar el plan y la trazabilidad de E.5 con la
> evidencia revisada de E.4.2. Conserva los informes históricos.

## Activación y prueba de humo posterior

Abrir una sesión nueva de Codex desde la raíz de CommerceCore para cargar la
configuración del proyecto y comprobar la lista de agentes. Si la aplicación
mantiene una sesión ya iniciada, reiniciarla antes de verificar. La necesidad
de reinicio no quedó comprobada en este corte. Los perfiles no se ejecutaron.

Prompt de humo para una sesión posterior, con autorización expresa:

> Sin inspeccionar el código funcional ni modificar archivos, enumera los
> agentes personalizados cargados y sus modelos y niveles de razonamiento.
> Usa analyst para devolver únicamente su nombre y configuración efectiva.
> Informa si el cliente o la cuenta no permiten seleccionarlo.

Repetir de forma secuencial con los demás perfiles si se necesita confirmar
su ejecución y configuración efectiva. No sustituir modelos sin autorización.
La inspección estática de TOML no acredita disponibilidad del modelo ni la
carga efectiva del agente.

## Medición de cuota

Registrar antes y después de cada hito los indicadores de uso disponibles en
el cliente o panel de la cuenta, el modelo, razonamiento, duración, número de
delegaciones y pruebas ejecutadas. Comparar tareas equivalentes y anotar
reinicios o límites de medición. No inferir consumo por número de archivos
ni registrar credenciales, tokens de acceso o datos sensibles.

## Fuentes oficiales

- [Subagentes y agentes personalizados](https://learn.chatgpt.com/docs/agent-configuration/subagents)
- [Instrucciones AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Referencia de configuración](https://learn.chatgpt.com/docs/config-file/config-reference)
