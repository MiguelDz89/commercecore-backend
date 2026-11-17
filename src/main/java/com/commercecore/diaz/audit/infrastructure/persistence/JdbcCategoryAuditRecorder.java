package com.commercecore.diaz.audit.infrastructure.persistence;
import com.commercecore.diaz.audit.application.CategoryAuditEvent;
import com.commercecore.diaz.audit.application.CategoryAuditRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import javax.sql.DataSource;
@ApplicationScoped
public class JdbcCategoryAuditRecorder implements CategoryAuditRecorder {
    private final DataSource source;
    private final ObjectMapper mapper;
    public JdbcCategoryAuditRecorder(DataSource source,ObjectMapper mapper) { this.source=source; this.mapper=mapper; }
    @Override @Transactional(Transactional.TxType.MANDATORY)
    public void record(CategoryAuditEvent event) {
        try(var c=source.getConnection(); var s=c.prepareStatement("""
                INSERT INTO audit_event(tenant_id,actor_user_id,entity_type,entity_id,action,before_data,after_data)
                VALUES (?,?,'CATEGORY',?,?,CAST(? AS jsonb),CAST(? AS jsonb))
                """)) {
            s.setObject(1,event.tenantId()); s.setObject(2,event.actorAccountId()); s.setObject(3,event.categoryId());
            s.setString(4,"CATEGORY_"+event.action().name());
            s.setString(5,event.before()==null ? null : mapper.writeValueAsString(event.before()));
            s.setString(6,mapper.writeValueAsString(event.after())); s.executeUpdate();
        } catch(Exception e) { throw new IllegalStateException("Unable to record category audit",e); }
    }
}
