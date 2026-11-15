package com.commercecore.diaz.identity;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import java.time.Duration;

public class KeycloakRealmResource implements QuarkusTestResourceLifecycleManager {
    static final String ADMIN = "u" + UUID.randomUUID().toString().replace("-", "");
    static final String MEMBER = "u" + UUID.randomUUID().toString().replace("-", "");
    static final String TENANT = "u" + UUID.randomUUID().toString().replace("-", "");
    static final String OTHER = "u" + UUID.randomUUID().toString().replace("-", "");
    static final String UNPROVISIONED = "u" + UUID.randomUUID().toString().replace("-", "");
    static final String PASSWORD = UUID.randomUUID() + "-" + UUID.randomUUID();
    private Path realmFile;
    private Path foreignRealmFile;
    private GenericContainer<?> keycloak;

    @Override
    public Map<String, String> start() {
        try {
            realmFile = Files.createTempFile("commercecore-test-realm-", ".json");
            String realm = """
                {"realm":"commercecore-test","enabled":true,
                 "roles":{"client":{"commercecore-api":[{"name":"PLATFORM_SUPER_ADMIN"},{"name":"TENANT_MANAGE"}],
                                    "other-client":[{"name":"PLATFORM_SUPER_ADMIN"}]}},
                 "clients":[
                   {"clientId":"commercecore-api","enabled":true,"publicClient":true,"directAccessGrantsEnabled":true,
                    "fullScopeAllowed":true,"protocol":"openid-connect",
                    "protocolMappers":[{"name":"api-audience","protocol":"openid-connect","protocolMapper":"oidc-audience-mapper",
                       "config":{"included.client.audience":"commercecore-api","access.token.claim":"true","id.token.claim":"false"}}]},
                   {"clientId":"other-client","enabled":true,"publicClient":true,"directAccessGrantsEnabled":true,
                    "fullScopeAllowed":true,"protocol":"openid-connect"}],
                 "users":[
                   {"username":"%s","enabled":true,"emailVerified":true,"firstName":"Test","lastName":"User","email":"admin@example.invalid","credentials":[{"type":"password","temporary":false,"value":"%s"}],"clientRoles":{"commercecore-api":["PLATFORM_SUPER_ADMIN"]}},
                   {"username":"%s","enabled":true,"emailVerified":true,"firstName":"Test","lastName":"User","email":"member@example.invalid","credentials":[{"type":"password","temporary":false,"value":"%s"}]},
                   {"username":"%s","enabled":true,"emailVerified":true,"firstName":"Test","lastName":"User","email":"tenant@example.invalid","credentials":[{"type":"password","temporary":false,"value":"%s"}],"clientRoles":{"commercecore-api":["TENANT_MANAGE"]}},
                   {"username":"%s","enabled":true,"emailVerified":true,"firstName":"Test","lastName":"User","email":"other@example.invalid","credentials":[{"type":"password","temporary":false,"value":"%s"}],"clientRoles":{"other-client":["PLATFORM_SUPER_ADMIN"]}},
                   {"username":"%s","enabled":true,"emailVerified":true,"firstName":"Test","lastName":"User","email":"unprovisioned@example.invalid","credentials":[{"type":"password","temporary":false,"value":"%s"}],"clientRoles":{"commercecore-api":["PLATFORM_SUPER_ADMIN"]}}]}
                """.formatted(ADMIN, PASSWORD, MEMBER, PASSWORD, TENANT, PASSWORD, OTHER, PASSWORD, UNPROVISIONED, PASSWORD);
            Files.writeString(realmFile, realm);
            foreignRealmFile = Files.createTempFile("commercecore-foreign-realm-", ".json");
            Files.writeString(foreignRealmFile, realm.replace("\"realm\":\"commercecore-test\"", "\"realm\":\"foreign-test\""));
            keycloak = new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.7.4"))
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "a" + UUID.randomUUID().toString().replace("-", ""))
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", UUID.randomUUID().toString())
                    .withExposedPorts(8080)
                    .withCopyToContainer(MountableFile.forHostPath(realmFile), "/opt/keycloak/data/import/realm.json")
                    .withCopyToContainer(MountableFile.forHostPath(foreignRealmFile), "/opt/keycloak/data/import/foreign.json")
                    .withCommand("start-dev", "--import-realm")
                    .waitingFor(Wait.forHttp("/realms/commercecore-test/.well-known/openid-configuration")
                            .forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));
            keycloak.start();
            return Map.of("quarkus.oidc.auth-server-url", "http://" + keycloak.getHost() + ":"
                            + keycloak.getMappedPort(8080) + "/realms/commercecore-test",
                    "quarkus.oidc.client-id", "commercecore-api");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create temporary test realm", e);
        }
    }

    @Override
    public void stop() {
        try {
            if (keycloak != null) keycloak.stop();
            if (realmFile != null) Files.deleteIfExists(realmFile);
            if (foreignRealmFile != null) Files.deleteIfExists(foreignRealmFile);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot remove temporary test realm", e);
        }
    }
}
