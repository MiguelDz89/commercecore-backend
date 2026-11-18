package com.commercecore.diaz.audit.infrastructure.persistence;

import com.commercecore.diaz.audit.application.PriceAuditRecorder;
import com.commercecore.diaz.audit.application.ProductCommercialHistoryQuery;
import java.sql.SQLException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
public class JdbcPriceAuditRecorder implements PriceAuditRecorder, ProductCommercialHistoryQuery {
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    public JdbcPriceAuditRecorder(DataSource dataSource, ObjectMapper mapper) { this.dataSource=dataSource;this.mapper=mapper; }
    @Transactional(Transactional.TxType.MANDATORY)
    public void sale(UUID tenant,UUID actor,UUID product,SaleSnapshot before,SaleSnapshot after){record(tenant,actor,"PRODUCT",product,"PRODUCT_SALE_PRICES_UPDATED",before,after);}
    @Transactional(Transactional.TxType.MANDATORY)
    public void cost(UUID tenant,UUID actor,UUID product,CostSnapshot before,CostSnapshot after){record(tenant,actor,"PRODUCT",product,"PRODUCT_COST_UPDATED",before,after);}
    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public boolean hasPriceHistory(UUID tenantId, UUID productId) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM audit_event WHERE tenant_id=? AND entity_type='PRODUCT' AND entity_id=?
                     AND action IN ('PRODUCT_SALE_PRICES_UPDATED','PRODUCT_COST_UPDATED')
                )
                """;
        try (var connection=dataSource.getConnection(); var statement=connection.prepareStatement(sql)) {
            statement.setObject(1,tenantId);
            statement.setObject(2,productId);
            try (var result=statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to check commercial history",error);
        }
    }

    @Override
    @Transactional(Transactional.TxType.MANDATORY)
    public void exchangeRate(UUID tenant, UUID actor, UUID rate, ExchangeRateSnapshot before, ExchangeRateSnapshot after) {
        record(tenant,actor,"PURCHASE_EXCHANGE_RATE",rate,"PURCHASE_EXCHANGE_RATE_UPDATED",before,after);
    }

    private void record(UUID tenant,UUID actor,String entityType,UUID entityId,String action,Object before,Object after){
        try(var c=dataSource.getConnection();var s=c.prepareStatement("INSERT INTO audit_event(tenant_id,actor_user_id,entity_type,entity_id,action,before_data,after_data) VALUES (?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))")){
            s.setObject(1,tenant);s.setObject(2,actor);s.setString(3,entityType);s.setObject(4,entityId);s.setString(5,action);s.setString(6,before==null?null:mapper.writeValueAsString(before));s.setString(7,mapper.writeValueAsString(after));s.executeUpdate();
        }catch(Exception e){throw new IllegalStateException("Unable to record price audit",e);}
    }
}
