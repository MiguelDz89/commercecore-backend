# Identidad OIDC y autoridad de plataforma

CommerceCore recibe access tokens Bearer mediante `quarkus-oidc`. Configure el
proveedor de producción mediante `QUARKUS_OIDC_AUTH_SERVER_URL` y configure
`QUARKUS_OIDC_CLIENT_ID` con el identificador exacto del cliente de la API.
Configure credenciales de cliente mediante el gestor de secretos del despliegue
cuando el proveedor las requiera. No almacene tokens ni secretos en Git.

El emisor se obtiene de los metadatos OIDC descubiertos; si el proveedor no
ofrece descubrimiento, configure y verifique explícitamente el emisor y sus
claves. El access token debe incluir la audiencia igual al identificador del
cliente de la API y un `sub` no vacío. Configure TLS y el almacén de confianza
del entorno sin desactivar la verificación del servidor. El backend acepta la
autoridad `PLATFORM_SUPER_ADMIN` únicamente desde la lista de roles de
`resource_access` correspondiente al cliente configurado.

## Bootstrap administrativo en Keycloak

Este procedimiento se realiza en la consola administrativa protegida de
Keycloak, nunca desde una API pública de CommerceCore:

1. Un operador autorizado identifica el realm y el cliente exacto de la API.
2. Crea el rol de cliente `PLATFORM_SUPER_ADMIN`, si aún no existe, y restringe
   la administración y asignación de ese rol a operadores autorizados.
3. Verifica la identidad elegible y asigna el rol de cliente a esa identidad.
4. Configura un audience mapper o alcance de cliente que incluya el cliente de
   la API en la audiencia del access token; comprueba que el token esté firmado
   por el emisor previsto y contenga `iss`, `sub`, `exp`, `aud` y el rol en
   `resource_access/<cliente>/roles`. No compartas el token en registros.
5. Comprueba con una operación protegida autorizada cuando exista. Los roles de
   realm, de otros clientes y los permisos de empresa no conceden esta facultad.
6. Para revocar el privilegio, retira el rol en Keycloak y aplica la política de
   revocación de sesiones/tokens del proveedor. Un JWT ya emitido puede seguir
   siendo válido hasta su vencimiento; usa una vida útil adecuada.

Conserva la trazabilidad mediante auditoría administrativa de Keycloak. El diseño
de auditoría global de CommerceCore está pendiente: `audit_event` exige empresa.
En desarrollo y pruebas se utiliza Keycloak Dev Services; las identidades de
prueba no son usuarios ni credenciales de producción.

Esta base de autorización no implementa todavía la creación de empresas, los
permisos por membresía ni un endpoint administrativo. Cada futuro caso de uso
debe verificar la autoridad en su capa de aplicación y probar su endpoint HTTP.
