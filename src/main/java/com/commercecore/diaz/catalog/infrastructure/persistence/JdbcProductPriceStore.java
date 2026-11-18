package com.commercecore.diaz.catalog.infrastructure.persistence;

import com.commercecore.diaz.catalog.application.port.ProductPriceStore;
import com.commercecore.diaz.catalog.domain.PriceSchedule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcProductPriceStore implements ProductPriceStore {
    private final DataSource source;
    public JdbcProductPriceStore(DataSource source) { this.source=source; }
    public Optional<Sale> sale(UUID tenant, UUID product) {
        try(var c=source.getConnection(); var s=c.prepareStatement("SELECT sale_price_configured,sale_price,currency_code,version,status,fractional_quantities_enabled FROM product WHERE tenant_id=? AND product_id=? AND sellable")) {
            s.setObject(1,tenant);s.setObject(2,product);
            try(var r=s.executeQuery()){return r.next()?Optional.of(new Sale(r.getBoolean(1),r.getBigDecimal(2),r.getString(3),r.getLong(4),"ARCHIVED".equals(r.getString(5)),r.getBoolean(6))):Optional.empty();}
        } catch(SQLException e){throw new IllegalStateException("Price persistence failed",e);}
    }
    public Optional<Cost> cost(UUID tenant, UUID product) {
        try(var c=source.getConnection(); var s=c.prepareStatement("SELECT cost_price,version,status,currency_code,cost_source_amount,cost_source_currency,cost_operation_date,cost_rate_date,cost_exchange_rate,cost_recorded_by FROM product WHERE tenant_id=? AND product_id=? AND sellable")) {
            s.setObject(1,tenant);s.setObject(2,product);
            try (var row=s.executeQuery()) {
                if (!row.next()) return Optional.empty();
                BigDecimal original=row.getBigDecimal(5);
                Acquisition evidence=original==null ? null : new Acquisition(original,row.getString(6),
                        row.getObject(7,LocalDate.class),row.getObject(8,LocalDate.class),
                        row.getBigDecimal(9),row.getObject(10,UUID.class));
                return Optional.of(new Cost(row.getBigDecimal(1),row.getLong(2),
                        "ARCHIVED".equals(row.getString(3)),row.getString(4),evidence));
            }
        } catch(SQLException e){throw new IllegalStateException("Price persistence failed",e);}
    }
    public List<PriceSchedule.Tier> tiers(UUID tenant,UUID product) {
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT minimum_quantity,unit_price FROM product_price_tier WHERE tenant_id=? AND product_id=? ORDER BY minimum_quantity")){
            s.setObject(1,tenant);s.setObject(2,product);var result=new ArrayList<PriceSchedule.Tier>();
            try(var r=s.executeQuery()){while(r.next())result.add(new PriceSchedule.Tier(r.getBigDecimal(1),r.getBigDecimal(2)));}return List.copyOf(result);
        }catch(SQLException e){throw new IllegalStateException("Price persistence failed",e);}
    }
    public boolean updateSale(UUID tenant,UUID product,UUID actor,long version,PriceSchedule schedule){
        try(var c=source.getConnection();var s=c.prepareStatement("UPDATE product SET sale_price=?,sale_price_configured=true,version=version+1,updated_by=?,updated_at=clock_timestamp() WHERE tenant_id=? AND product_id=? AND sellable AND status<>'ARCHIVED' AND version=?")){
            s.setBigDecimal(1,schedule.retailPrice());s.setObject(2,actor);s.setObject(3,tenant);s.setObject(4,product);s.setLong(5,version);if(s.executeUpdate()!=1)return false;
            try(var d=c.prepareStatement("DELETE FROM product_price_tier WHERE tenant_id=? AND product_id=?")){d.setObject(1,tenant);d.setObject(2,product);d.executeUpdate();}
            try(var i=c.prepareStatement("INSERT INTO product_price_tier(tenant_id,product_id,minimum_quantity,unit_price) VALUES (?,?,?,?)")){
                for(var tier:schedule.tiers()){i.setObject(1,tenant);i.setObject(2,product);i.setBigDecimal(3,tier.minimumQuantity());i.setBigDecimal(4,tier.unitPrice());i.addBatch();}i.executeBatch();
            }return true;
        }catch(SQLException e){throw new IllegalStateException("Price persistence failed",e);}
    }
    public boolean updateCost(UUID tenant,UUID product,UUID actor,long version,BigDecimal cost,Acquisition evidence){
        String sql="""
                UPDATE product SET cost_price=?,cost_source_amount=?,cost_source_currency=?,cost_operation_date=?,
                    cost_rate_date=?,cost_exchange_rate=?,cost_recorded_by=?,
                    version=version+1,updated_by=?,updated_at=clock_timestamp()
                WHERE tenant_id=? AND product_id=? AND sellable AND status<>'ARCHIVED' AND version=?
                """;
        try (var c=source.getConnection(); var s=c.prepareStatement(sql)) {
            s.setBigDecimal(1,cost);
            s.setBigDecimal(2,evidence==null?null:evidence.sourceAmount());
            s.setString(3,evidence==null?null:evidence.sourceCurrency());
            s.setObject(4,evidence==null?null:evidence.operationDate());
            s.setObject(5,evidence==null?null:evidence.rateDate());
            s.setBigDecimal(6,evidence==null?null:evidence.rate());
            s.setObject(7,evidence==null?null:evidence.recordedBy());
            s.setObject(8,actor);
            s.setObject(9,tenant);
            s.setObject(10,product);
            s.setLong(11,version);
            return s.executeUpdate()==1;
        }catch(SQLException e){throw new IllegalStateException("Price persistence failed",e);}
    }
}
