package com.commercecore.diaz.identity.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProductPricingV5MigrationTest {
    @Test
    void upgradesHistoricalDataAndEnforcesPricingConstraints() throws Exception {
        String password = UUID.randomUUID().toString();
        try (var pg = new GenericContainer<>(DockerImageName.parse("postgres:18"))
                .withEnv("POSTGRES_USER", "testuser").withEnv("POSTGRES_PASSWORD", password)
                .withEnv("POSTGRES_DB", "pricing_test").withExposedPorts(5432)) {
            pg.start();
            String url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/pricing_test";
            Flyway.configure().dataSource(url, "testuser", password).target("4").load().migrate();
            UUID t=UUID.randomUUID(), other=UUID.randomUUID(), category=UUID.randomUUID(), product=UUID.randomUUID();
            try (var c=DriverManager.getConnection(url,"testuser",password); var s=c.createStatement()) {
                s.execute("INSERT INTO tenant(tenant_id,slug,legal_name,display_name) VALUES ('"+t+"','test-one','Test','Test'),('"+other+"','test-two','Test','Test')");
                s.execute("INSERT INTO category(tenant_id,category_id,code,name) VALUES ('"+t+"','"+category+"','C','Category')");
                s.execute("INSERT INTO product(tenant_id,product_id,category_id,sku,name,sale_price) VALUES ('"+t+"','"+product+"','"+category+"','SKU','Product',12.34)");
            }
            assertEquals(1,Flyway.configure().dataSource(url,"testuser",password).load().migrate().migrationsExecuted);
            try (var c=DriverManager.getConnection(url,"testuser",password); var s=c.createStatement()) {
                try(var r=s.executeQuery("SELECT sale_price, sale_price_configured, cost_price, status FROM product WHERE product_id='"+product+"'")) {
                    assertTrue(r.next()); assertEquals("12.34",r.getBigDecimal(1).toPlainString()); assertFalse(r.getBoolean(2)); assertNull(r.getBigDecimal(3)); assertEquals("DRAFT",r.getString(4));
                }
                String prefix="INSERT INTO product_price_tier(tenant_id,product_id,minimum_quantity,unit_price) VALUES ";
                s.execute(prefix+"('"+t+"','"+product+"',1.250,9.99)");
                assertThrows(SQLException.class,()->s.execute(prefix+"('"+t+"','"+product+"',1.25,8)"));
                assertThrows(SQLException.class,()->s.execute(prefix+"('"+t+"','"+product+"',0,8)"));
                assertThrows(SQLException.class,()->s.execute(prefix+"('"+t+"','"+product+"',2,-1)"));
                assertThrows(SQLException.class,()->s.execute(prefix+"('"+other+"','"+product+"',2,8)"));
                assertThrows(SQLException.class,()->s.execute("UPDATE product SET cost_price=-1 WHERE product_id='"+product+"'"));
                try(var r=s.executeQuery("SELECT minimum_quantity,unit_price FROM product_price_tier")) { assertTrue(r.next()); assertEquals("1.250",r.getBigDecimal(1).toPlainString()); assertEquals("9.99",r.getBigDecimal(2).toPlainString()); }
                try(var r=s.executeQuery("SELECT count(*) FROM permission WHERE permission_code IN ('PRODUCT_COST_READ','PRODUCT_COST_UPDATE','PRODUCT_PRICE_TIER_READ')")) { assertTrue(r.next()); assertEquals(3,r.getInt(1)); }
                try(var r=s.executeQuery("SELECT count(*) FROM role_permission WHERE permission_code IN ('PRODUCT_COST_READ','PRODUCT_COST_UPDATE','PRODUCT_PRICE_TIER_READ')")) { assertTrue(r.next()); assertEquals(0,r.getInt(1)); }
            }
        }
    }
}
