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
            assertEquals(1,Flyway.configure().dataSource(url,"testuser",password).target("5").load().migrate().migrationsExecuted);
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
            assertEquals(1, Flyway.configure().dataSource(url,"testuser",password).target("6").load().migrate().migrationsExecuted);
            try (var connection=DriverManager.getConnection(url,"testuser",password);
                    var statement=connection.prepareStatement("""
                        SELECT p.base_unit, t.fractional_quantities_enabled, p.fractional_quantities_enabled,
                               p.sale_price, pt.minimum_quantity, pt.unit_price
                        FROM product p JOIN tenant t ON t.tenant_id=p.tenant_id
                        JOIN product_price_tier pt ON pt.tenant_id=p.tenant_id AND pt.product_id=p.product_id
                        WHERE p.tenant_id=? AND p.product_id=?
                        """)) {
                statement.setObject(1,t);
                statement.setObject(2,product);
                try (var result=statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("UNIT",result.getString(1));
                    assertFalse(result.getBoolean(2));
                    assertFalse(result.getBoolean(3));
                    assertEquals("12.34",result.getBigDecimal(4).toPlainString());
                    assertEquals("1.250",result.getBigDecimal(5).toPlainString());
                    assertEquals("9.99",result.getBigDecimal(6).toPlainString());
                    assertFalse(result.next());
                }
            }
            try(var connection=DriverManager.getConnection(url,"testuser",password);
                    var write=connection.prepareStatement("UPDATE product SET cost_price=12.50 WHERE tenant_id=? AND product_id=?")) {
                write.setObject(1,t);
                write.setObject(2,product);
                assertEquals(1,write.executeUpdate());
            }
            assertEquals(1,Flyway.configure().dataSource(url,"testuser",password).target("7").load().migrate().migrationsExecuted);
            try(var connection=DriverManager.getConnection(url,"testuser",password);
                    var read=connection.prepareStatement("""
                        SELECT currency_code,cost_price,cost_source_amount,cost_exchange_rate
                        FROM product WHERE tenant_id=? AND product_id=?
                        """)) {
                read.setObject(1,t);
                read.setObject(2,product);
                try(var row=read.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("PEN",row.getString(1));
                    assertEquals("12.50",row.getBigDecimal(2).toPlainString());
                    assertNull(row.getBigDecimal(3));
                    assertNull(row.getBigDecimal(4));
                }
            }
            assertEquals(1,Flyway.configure().dataSource(url,"testuser",password).target("8").load().migrate().migrationsExecuted);
            UUID group=UUID.randomUUID(), variant=UUID.randomUUID(), otherCategory=UUID.randomUUID();
            try(var connection=DriverManager.getConnection(url,"testuser",password);
                    var statement=connection.createStatement()) {
                try(var row=statement.executeQuery("SELECT sku,structure_type,parent_id,sale_price,cost_price FROM product WHERE product_id='"+product+"'")) {
                    assertTrue(row.next());
                    assertEquals("SKU",row.getString(1));
                    assertEquals("SIMPLE",row.getString(2));
                    assertNull(row.getObject(3));
                    assertEquals("12.34",row.getBigDecimal(4).toPlainString());
                    assertEquals("12.50",row.getBigDecimal(5).toPlainString());
                }
                statement.execute("INSERT INTO category(tenant_id,category_id,code,name) VALUES ('"+other+"','"+otherCategory+"','C','Category')");
                statement.execute("INSERT INTO product(tenant_id,product_id,category_id,name,structure_type) VALUES ('"+t+"','"+group+"','"+category+"','Casco','VARIABLE')");
                String insert="""
                    INSERT INTO product(tenant_id,product_id,category_id,sku,name,structure_type,parent_id,variant_attributes)
                    VALUES ('%s','%s','%s','%s','Casco','VARIANT','%s','{"color":"black","size":"M"}')
                    """;
                statement.execute(insert.formatted(t,variant,category,"VARIANT-M",group));
                assertEquals("23505",assertThrows(SQLException.class,()->statement.execute(
                        insert.formatted(t,UUID.randomUUID(),category,"OTHER-SKU",group))).getSQLState());
                assertEquals("23503",assertThrows(SQLException.class,()->statement.execute(
                        insert.formatted(other,UUID.randomUUID(),otherCategory,"CROSS-TENANT",group))).getSQLState());
                assertEquals("23503",assertThrows(SQLException.class,()->statement.execute(
                        insert.formatted(t,UUID.randomUUID(),category,"NESTED-CHILD",variant))).getSQLState());
                assertEquals("23514",assertThrows(SQLException.class,()->statement.execute(
                        "UPDATE product SET sale_price_configured=true WHERE product_id='"+group+"'")).getSQLState());
                assertEquals("23503",assertThrows(SQLException.class,()->statement.execute(
                        "INSERT INTO product_price_tier(tenant_id,product_id,minimum_quantity,unit_price) VALUES ('"+t+"','"+group+"',2,8)")).getSQLState());
            }
            try (var connection=DriverManager.getConnection(url,"testuser",password);
                    var statement=connection.createStatement()) {
                statement.execute("UPDATE product SET status='ACTIVE' WHERE product_id='"+product+"'");
                statement.execute("UPDATE product SET sale_price_configured=true,version=7 WHERE product_id='"+product+"'");
                statement.execute("UPDATE product SET status='INACTIVE' WHERE product_id='"+variant+"'");
                statement.execute("UPDATE product SET status='ARCHIVED' WHERE product_id='"+group+"'");
            }
            assertEquals(1,Flyway.configure().dataSource(url,"testuser",password).target("9").load().migrate().migrationsExecuted);
            try (var connection=DriverManager.getConnection(url,"testuser",password);
                    var statement=connection.createStatement()) {
                statement.execute("UPDATE product SET status='PENDING_RECEIPT' WHERE product_id='"+product+"'");
                assertEquals("23514",assertThrows(SQLException.class,()->statement.execute(
                        "UPDATE product SET status='UNKNOWN' WHERE product_id='"+product+"'")).getSQLState());
                try(var query=connection.prepareStatement("""
                        SELECT p.product_id,p.sku,p.status,p.sale_price,p.cost_price,p.parent_id,
                               p.structure_type,p.variant_attributes,t.price_tier_id
                        FROM product p LEFT JOIN product_price_tier t
                          ON t.tenant_id=p.tenant_id AND t.product_id=p.product_id
                        WHERE p.tenant_id=? ORDER BY p.product_id
                        """)) {
                    query.setObject(1,t);
                    try(var rows=query.executeQuery()) {
                    boolean simple=false, variable=false, variantRow=false;
                    while(rows.next()) {
                        UUID id=rows.getObject(1,UUID.class);
                        if(id.equals(product)) {
                            simple=true; assertEquals("SKU",rows.getString(2)); assertEquals("PENDING_RECEIPT",rows.getString(3));
                            assertEquals("12.34",rows.getBigDecimal(4).toPlainString()); assertEquals("12.50",rows.getBigDecimal(5).toPlainString());
                            assertNotNull(rows.getObject(9));
                        } else if(id.equals(group)) {
                            variable=true; assertEquals("ARCHIVED",rows.getString(3)); assertNull(rows.getString(2));
                        } else if(id.equals(variant)) {
                            variantRow=true; assertEquals("VARIANT",rows.getString(7));
                            assertNotNull(rows.getObject(6));
                            assertNotNull(rows.getObject(8));
                        }
                    }
                    assertTrue(simple && variable && variantRow);
                    }
                }
                try(var rows=statement.executeQuery("""
                        SELECT count(*) FROM permission WHERE permission_code='PRODUCT_STATUS_UPDATE'
                        """)) { assertTrue(rows.next()); assertEquals(1,rows.getInt(1)); }
                try(var rows=statement.executeQuery("""
                        SELECT count(*) FROM role_permission WHERE permission_code='PRODUCT_STATUS_UPDATE'
                        """)) { assertTrue(rows.next()); assertEquals(0,rows.getInt(1)); }
            }
            assertEquals(0,Flyway.configure().dataSource(url,"testuser",password).target("9").load().migrate().migrationsExecuted);
            var beforeV10 = commercialSnapshot(url,"testuser",password,t);
            assertEquals(1,Flyway.configure().dataSource(url,"testuser",password).target("10").load().migrate().migrationsExecuted);
            assertEquals(beforeV10,commercialSnapshot(url,"testuser",password,t));
            try (var connection=DriverManager.getConnection(url,"testuser",password); var statement=connection.createStatement()) {
                try(var rows=statement.executeQuery("SELECT count(*) FROM product WHERE tenant_id='"+t+"' AND NOT published AND NOT ever_published")) {
                    assertTrue(rows.next()); assertEquals(3,rows.getInt(1));
                }
                statement.execute("UPDATE product SET status='ACTIVE',published=true,ever_published=true WHERE product_id='"+group+"'");
                try(var rows=statement.executeQuery("SELECT published,ever_published,parent_id,sku,status FROM product WHERE product_id='"+variant+"'")) {
                    assertTrue(rows.next()); assertFalse(rows.getBoolean(1)); assertFalse(rows.getBoolean(2));
                    assertEquals(group,rows.getObject(3,UUID.class)); assertEquals("VARIANT-M",rows.getString(4)); assertEquals("INACTIVE",rows.getString(5));
                }
                assertEquals("23514",assertThrows(SQLException.class,()->statement.execute(
                        "UPDATE product SET published=true,ever_published=true WHERE product_id='"+variant+"'")).getSQLState());
                assertEquals("23514",assertThrows(SQLException.class,()->statement.execute(
                        "UPDATE product SET status='ACTIVE',published=true,ever_published=false WHERE product_id='"+variant+"'")).getSQLState());
                statement.execute("INSERT INTO product(tenant_id,category_id,sku,name) VALUES ('"+t+"','"+category+"','NEW-SKU','New')");
                try(var rows=statement.executeQuery("SELECT published,ever_published FROM product WHERE tenant_id='"+t+"' AND sku='NEW-SKU'")) {
                    assertTrue(rows.next()); assertFalse(rows.getBoolean(1)); assertFalse(rows.getBoolean(2));
                }
            }
        }
    }

    private static java.util.List<java.util.List<Object>> commercialSnapshot(String url,String user,String password,UUID tenant)
            throws SQLException {
        try (var connection=DriverManager.getConnection(url,user,password);
                var query=connection.prepareStatement("""
                        SELECT p.product_id,p.category_id,p.sku,p.parent_id,p.structure_type,p.status,
                               p.sale_price,p.sale_price_configured,p.cost_price,p.currency_code,p.version,
                               t.price_tier_id,t.minimum_quantity,t.unit_price
                        FROM product p LEFT JOIN product_price_tier t
                          ON t.tenant_id=p.tenant_id AND t.product_id=p.product_id
                        WHERE p.tenant_id=? ORDER BY p.product_id,t.minimum_quantity
                        """)) {
            query.setObject(1,tenant);
            var snapshot=new java.util.ArrayList<java.util.List<Object>>();
            try(var rows=query.executeQuery()) {
                while(rows.next()) {
                    var row=new java.util.ArrayList<Object>();
                    for(int column=1;column<=14;column++) row.add(rows.getObject(column));
                    snapshot.add(row);
                }
            }
            assertEquals(3,snapshot.size());
            assertTrue(snapshot.stream().anyMatch(row -> Boolean.TRUE.equals(row.get(7))
                    && new java.math.BigDecimal("12.34").compareTo((java.math.BigDecimal) row.get(6))==0
                    && new java.math.BigDecimal("12.50").compareTo((java.math.BigDecimal) row.get(8))==0
                    && "PEN".equals(row.get(9)) && Long.valueOf(7).equals(row.get(10))
                    && new java.math.BigDecimal("1.250").compareTo((java.math.BigDecimal) row.get(12))==0
                    && new java.math.BigDecimal("9.99").compareTo((java.math.BigDecimal) row.get(13))==0));
            return snapshot;
        }
    }

    @Test
    void installsLifecycleMigrationFromAnEmptyDatabase() {
        String password=UUID.randomUUID().toString();
        try (var pg=new GenericContainer<>(DockerImageName.parse("postgres:18"))
                .withEnv("POSTGRES_USER","testuser").withEnv("POSTGRES_PASSWORD",password)
                .withEnv("POSTGRES_DB","lifecycle_test").withExposedPorts(5432)) {
            pg.start();
            String url="jdbc:postgresql://"+pg.getHost()+":"+pg.getMappedPort(5432)+"/lifecycle_test";
            var configuration=Flyway.configure().dataSource(url,"testuser",password);
            assertEquals(10,configuration.load().migrate().migrationsExecuted);
            assertEquals(0,configuration.load().migrate().migrationsExecuted);
            try(var connection=DriverManager.getConnection(url,"testuser",password);
                    var statement=connection.createStatement();
                    var rows=statement.executeQuery("SELECT count(*) FROM pg_constraint WHERE conname='product_status_check'")) {
                assertTrue(rows.next()); assertEquals(1,rows.getInt(1));
            } catch(SQLException error) {
                throw new IllegalStateException(error);
            }
        }
    }
}
