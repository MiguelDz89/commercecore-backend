package com.commercecore.diaz.identity;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Exclusively test-owned services; never points at a persistent database. */
public class SprintOneIntegrationResource implements QuarkusTestResourceLifecycleManager {
    static GenericContainer<?> postgres;
    static String jdbc, username, password, issuer;
    private final KeycloakRealmResource keycloak=new KeycloakRealmResource();
    @Override public Map<String,String> start() {
        username="commercecore_it"; password=UUID.randomUUID().toString();
        postgres=new GenericContainer<>(DockerImageName.parse("postgres:18"))
                .withEnv("POSTGRES_USER",username).withEnv("POSTGRES_PASSWORD",password)
                .withEnv("POSTGRES_DB","commercecore_it").withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s",2).withStartupTimeout(Duration.ofMinutes(2)));
        try {
            postgres.start();
            jdbc="jdbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/commercecore_it?connectTimeout=2&socketTimeout=3";
            var settings=new HashMap<>(keycloak.start()); issuer=settings.get("quarkus.oidc.auth-server-url");
            settings.put("quarkus.datasource.jdbc.url",jdbc);
            settings.put("quarkus.datasource.username",username);
            settings.put("quarkus.datasource.password",password);
            settings.put("quarkus.datasource.jdbc.acquisition-timeout","3S");
            return settings;
        } catch(RuntimeException e) { stop(); throw e; }
    }
    @Override public void stop() {
        try { keycloak.stop(); } finally { if(postgres!=null) postgres.stop(); }
    }
}
