package com.commercecore.diaz.audit.infrastructure.persistence;
import com.commercecore.diaz.audit.application.ProductAuditEvent;
import com.commercecore.diaz.audit.application.ProductAuditRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import javax.sql.DataSource;
@ApplicationScoped
public class JdbcProductAuditRecorder implements ProductAuditRecorder {
    private final DataSource source;private final ObjectMapper mapper;
    public JdbcProductAuditRecorder(DataSource source,ObjectMapper mapper){this.source=source;this.mapper=mapper;}
    @Override @Transactional(Transactional.TxType.MANDATORY)
    public void record(ProductAuditEvent e){
        try(var c=source.getConnection();var s=c.prepareStatement("INSERT INTO audit_event(tenant_id,actor_user_id,entity_type,entity_id,action,before_data,after_data) VALUES (?,?,'PRODUCT',?,?,CAST(? AS jsonb),CAST(? AS jsonb))")) {
            s.setObject(1,e.tenantId());s.setObject(2,e.actorAccountId());s.setObject(3,e.productId());s.setString(4,"PRODUCT_"+e.action().name());
            s.setString(5,e.before()==null?null:mapper.writeValueAsString(e.before()));s.setString(6,mapper.writeValueAsString(e.after()));s.executeUpdate();
        }catch(Exception ex){throw new IllegalStateException("Unable to record product audit",ex);}
    }
}
