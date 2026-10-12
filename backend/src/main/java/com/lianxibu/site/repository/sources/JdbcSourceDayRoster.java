package com.lianxibu.site.repository.sources;

import com.lianxibu.site.domain.sources.SourceDayRoster;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Full original source metadata in the caller's repeatable-read snapshot, without configuration exposure. */
public final class JdbcSourceDayRoster implements SourceDayRoster.Api {
    private static final int MAX_CONFIG_BYTES=65536,MAX_TOTAL_CONFIG_BYTES=1048576;
    private static final String COLUMNS="id,category,revision,config_revision,activation_stage,state,config_valid_until,"
            +"draft_config_json IS NOT NULL AS draft_present,effective_config_json IS NOT NULL AS effective_present,"
            +"OCTET_LENGTH(effective_config_json) AS effective_bytes,"
            +"CASE WHEN OCTET_LENGTH(effective_config_json)<=65536 THEN effective_config_json ELSE NULL END AS effective_body";
    private final JdbcTemplate sql;private final Clock clock;
    public JdbcSourceDayRoster(DataSource source,Clock clock){sql=new JdbcTemplate(Objects.requireNonNull(source));this.clock=Objects.requireNonNull(clock);}
    @Override public SourceDayRoster.Evidence read(){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_REPEATABLE_READ))
            throw new IllegalStateException("source day roster requires read-only repeatable-read transaction");
        try{
            var totals=sql.query("SELECT COUNT(*) AS source_count,COALESCE(SUM(OCTET_LENGTH(effective_config_json)),0) AS config_bytes FROM sources",
                    (rs,index)->new BigInteger[]{integer(rs.getString("source_count")),integer(rs.getString("config_bytes"))});
            if(totals.size()!=1)throw invalid();var total=totals.getFirst();
            if(total[0].compareTo(BigInteger.valueOf(SourceDayRoster.MAX_SOURCES))>0||total[1].compareTo(BigInteger.valueOf(MAX_TOTAL_CONFIG_BYTES))>0)throw new SourceDayRoster.CapacityExceeded();
            var sources=sql.query("SELECT "+COLUMNS+" FROM sources ORDER BY id LIMIT 10001",(rs,index)->row(rs));
            if(sources.size()!=total[0].intValueExact())throw invalid();
            return new SourceDayRoster.Evidence(clock.instant().truncatedTo(ChronoUnit.MICROS),total[0].longValueExact(),sources);
        }catch(SourceDayRoster.CapacityExceeded capacity){throw capacity;}catch(RuntimeException unavailable){return null;}
    }
    private static SourceDayRoster.RawSource row(ResultSet rs)throws SQLException{
        boolean draft=rs.getBoolean("draft_present");if(rs.wasNull())throw invalid();
        boolean effective=rs.getBoolean("effective_present");if(rs.wasNull())throw invalid();
        String byteCount=rs.getString("effective_bytes"),body=rs.getString("effective_body"),hash=null;
        if(effective){
            BigInteger bytes=integer(byteCount);if(bytes.compareTo(BigInteger.valueOf(MAX_CONFIG_BYTES))>0)throw new SourceDayRoster.CapacityExceeded();
            if(body==null||bytes.signum()==0||body.getBytes(StandardCharsets.UTF_8).length!=bytes.intValueExact())throw invalid();hash=sha256(body);
        }else if(byteCount!=null||body!=null)throw invalid();
        var valid=rs.getTimestamp("config_valid_until",Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        return new SourceDayRoster.RawSource(rs.getString("id"),rs.getString("category"),integer(rs.getString("revision")).longValueExact(),integer(rs.getString("config_revision")).longValueExact(),
                rs.getString("activation_stage"),rs.getString("state"),valid==null?null:valid.toInstant(),draft,effective,hash);
    }
    private static BigInteger integer(String value){if(value==null||!value.matches("[0-9]+"))throw invalid();return new BigInteger(value);}
    private static String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("actual source day roster unavailable");}
    @Override public String toString(){return "JdbcSourceDayRoster[redacted]";}
}
