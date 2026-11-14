# Diagnóstico: cierre de la aplicación en pruebas de integración Windows

En ejecuciones de `mvnw.cmd clean verify -DskipITs=false`,
`GreetingResourceIT` pasó y Failsafe registró `BUILD SUCCESS`, pero se observó
`Process exited with non-zero status: 1` después del resultado de la prueba.
El registro de la aplicación no mostró una excepción de cierre de Quarkus,
PostgreSQL ni Keycloak, y no contiene evidencia suficiente de un cierre limpio.

La inspección del bytecode de `quarkus-test-common` 3.40.1.1 muestra que
`DefaultJarLauncher.close()` invoca `LauncherUtil.destroyProcess()`, que llama a
`Process.destroy()` y, si el proceso sigue activo, a `destroyForcibly()` tras la
espera. El lector `LauncherUtil$ProcessReader` imprime el código cuando es
distinto de cero. La terminación solicitada por el runner es una explicación
compatible con lo observado en Windows, pero no se ha medido directamente la
causa del código 1 ni demostrado que todos los recursos cierren limpiamente.

En el mismo arranque apareció la advertencia de clave no reconocida
`quarkus.log.category.io.quarkus.level`. El argumento con comillas para esa
categoría lo añade `DefaultJarLauncher`; la diferencia observada al procesar el
argumento en Windows requiere investigación adicional. No se ha cambiado la
configuración para ocultar ninguno de los mensajes.

Incidencia independiente de Identity: reproducir con instrumentación de salida
del proceso y comparar el cierre controlado con una parada normal, sin registrar
tokens ni credenciales. Mantener Failsafe activo y no interpretar por sí solo el
mensaje como una prueba de fallo funcional o de cierre limpio.
