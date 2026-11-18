package com.commercecore.diaz.catalog.infrastructure.persistence;

import com.commercecore.diaz.catalog.application.port.ExchangeRateStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcExchangeRateStore implements ExchangeRateStore {
    private final DataSource source;

    public JdbcExchangeRateStore(DataSource source) {
        this.source = source;
    }

    @Override
    public Optional<Entry> onOrBefore(UUID tenant, String from, String to, LocalDate date) {
        String sql = """
                SELECT exchange_rate_id,rate_date,rate,recorded_by FROM purchase_exchange_rate
                WHERE tenant_id=? AND source_currency=? AND target_currency=? AND rate_date<=?
                ORDER BY rate_date DESC LIMIT 1
                """;
        try (var connection=source.getConnection(); var statement=connection.prepareStatement(sql)) {
            statement.setObject(1, tenant);
            statement.setString(2, from);
            statement.setString(3, to);
            statement.setObject(4, date);
            try (var result=statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Exchange rate lookup failed",error);
        }
    }

    @Override
    public Entry put(UUID tenant, String from, String to, LocalDate date, BigDecimal rate, UUID actor) {
        String sql = """
                INSERT INTO purchase_exchange_rate (tenant_id,source_currency,target_currency,rate_date,rate,recorded_by)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (tenant_id,source_currency,target_currency,rate_date)
                DO UPDATE SET rate=excluded.rate, recorded_by=excluded.recorded_by, updated_at=clock_timestamp()
                RETURNING exchange_rate_id,rate_date,rate,recorded_by
                """;
        try (var connection=source.getConnection(); var statement=connection.prepareStatement(sql)) {
            statement.setObject(1, tenant);
            statement.setString(2, from);
            statement.setString(3, to);
            statement.setObject(4, date);
            statement.setBigDecimal(5, rate);
            statement.setObject(6, actor);
            try (var result=statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing exchange rate result");
                return read(result);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Exchange rate write failed",error);
        }
    }

    private static Entry read(ResultSet row) throws SQLException {
        return new Entry(row.getObject(1, UUID.class), row.getObject(2, LocalDate.class),
                row.getBigDecimal(3), row.getObject(4, UUID.class));
    }
}
