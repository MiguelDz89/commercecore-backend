package com.commercecore.diaz.identity.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import com.commercecore.diaz.identity.application.AuthorizationIntegrityException;
import com.commercecore.diaz.identity.domain.ActorId;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RbacV4MigrationTest {
    @Test
    void validHistoricalDatabaseUpgradesFromVersionThree() throws Exception {
        String password=UUID.randomUUID().toString();
        try(var pg=new GenericContainer<>(DockerImageName.parse("postgres:18"))
                .withEnv("POSTGRES_USER","commercecore_test").withEnv("POSTGRES_PASSWORD",password)
                .withEnv("POSTGRES_DB","commercecore_test").withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s",2).withStartupTimeout(Duration.ofMinutes(2)))) {
            pg.start();
            String url="jdbc:postgresql://"+pg.getHost()+":"+pg.getMappedPort(5432)+"/commercecore_test";
            Flyway.configure().dataSource(url,"commercecore_test",password).target("3").load().migrate();
            UUID tenant=UUID.randomUUID(), role=UUID.randomUUID();
            try(var c=DriverManager.getConnection(url,"commercecore_test",password); var s=c.createStatement()) {
                s.execute("INSERT INTO tenant(tenant_id,slug,legal_name,display_name) VALUES ('"+tenant+"','v4-"+tenant+"','Test','Test')");
                s.execute("INSERT INTO app_role(tenant_id,role_id,code,name) VALUES ('"+tenant+"','"+role+"','ordinary','Ordinary')");
                s.execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES ('"+tenant+"','"+role+"','PRODUCT_READ')");
            }
            assertEquals(1,Flyway.configure().dataSource(url,"commercecore_test",password).target("4").load().migrate().migrationsExecuted);
            try(var c=DriverManager.getConnection(url,"commercecore_test",password); var s=c.createStatement()) {
                try(var r=s.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE version='4' AND success")) { assertTrue(r.next()); assertEquals(1,r.getInt(1)); }
                try(var r=s.executeQuery("SELECT count(*) FROM role_permission WHERE role_id='"+role+"' AND permission_code='PRODUCT_READ'")) { assertTrue(r.next()); assertEquals(1,r.getInt(1)); }
            }
        }
    }

    @Test
    void incompatibleHistoricalDataAbortsMigrationWithoutRepair() throws Exception {
        String password=UUID.randomUUID().toString();
        try(var pg=new GenericContainer<>(DockerImageName.parse("postgres:18"))
                .withEnv("POSTGRES_USER","commercecore_test").withEnv("POSTGRES_PASSWORD",password)
                .withEnv("POSTGRES_DB","commercecore_test").withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s",2).withStartupTimeout(Duration.ofMinutes(2)))) {
            pg.start();
            String url="jdbc:postgresql://"+pg.getHost()+":"+pg.getMappedPort(5432)+"/commercecore_test";
            Flyway.configure().dataSource(url,"commercecore_test",password).target("3").load().migrate();
            UUID tenant=UUID.randomUUID(), role=UUID.randomUUID(), user=UUID.randomUUID(), membership=UUID.randomUUID();
            try(var c=DriverManager.getConnection(url,"commercecore_test",password); var s=c.createStatement()) {
                s.execute("INSERT INTO tenant(tenant_id,slug,legal_name,display_name) VALUES ('"+tenant+"','v4-"+tenant+"','Test','Test')");
                s.execute("INSERT INTO app_role(tenant_id,role_id,code,name) VALUES ('"+tenant+"','"+role+"','ordinary','Ordinary')");
                s.execute("INSERT INTO role_permission(tenant_id,role_id,permission_code) VALUES ('"+tenant+"','"+role+"','USER_MANAGE')");
                s.execute("INSERT INTO user_account(user_id,oidc_issuer,oidc_subject) VALUES ('"+user+"','https://issuer.test','historical')");
                s.execute("INSERT INTO tenant_membership(tenant_id,membership_id,user_id,status) VALUES ('"+tenant+"','"+membership+"','"+user+"','ACTIVE')");
                s.execute("INSERT INTO membership_role(tenant_id,membership_id,role_id) VALUES ('"+tenant+"','"+membership+"','"+role+"')");
            }
            var ds=new PGSimpleDataSource(); ds.setURL(url); ds.setUser("commercecore_test"); ds.setPassword(password);
            assertThrows(AuthorizationIntegrityException.class, () -> new JdbcTenantPermissionLookup(ds)
                    .hasPermission(new ActorId("https://issuer.test","historical"),tenant,"USER_MANAGE"));
            FlywayException failure=assertThrows(FlywayException.class,() -> Flyway.configure().dataSource(url,"commercecore_test",password).target("4").load().migrate());
            assertTrue(failure.getMessage().contains("RBAC integrity"));
            try(var c=DriverManager.getConnection(url,"commercecore_test",password); var s=c.createStatement()) {
                try(var r=s.executeQuery("SELECT count(*) FROM role_permission WHERE role_id='"+role+"' AND permission_code='USER_MANAGE'")) { assertTrue(r.next()); assertEquals(1,r.getInt(1)); }
                try(var r=s.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE version='4' AND success")) { assertTrue(r.next()); assertEquals(0,r.getInt(1)); }
                try(var r=s.executeQuery("SELECT count(*) FROM pg_constraint WHERE conname='ck_role_reserved_code'")) { assertTrue(r.next()); assertEquals(0,r.getInt(1)); }
            }
        }
    }
}
