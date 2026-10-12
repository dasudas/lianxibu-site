package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native origin locators only. Original registered source, full P1 input/result and immutable body must be proved separately. */
public final class JdbcEntityHeatOriginHeaders {
    public record Header(String itemId,String runId,String sourceId,String sourceItemId,String entityId,LocalDate day,long revision,String state,boolean effectiveValid,Instant createdAt){@Override public String toString(){return "NativeEntityHeatOriginLocator[redacted]";}}
    private final JdbcTemplate sql;
    public JdbcEntityHeatOriginHeaders(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));}
    public Header read(String itemId){readonly();WireValues.id(itemId,false);var rows=sql.query("SELECT i.id,i.run_id,i.source_id,CASE WHEN OCTET_LENGTH(i.source_item_id) BETWEEN 1 AND 4096 THEN i.source_item_id ELSE NULL END,i.entity_id,i.category,i.revision,i.terminal_state,i.state,i.effective_valid,i.created_at,r.business_date FROM source_run_items i LEFT JOIN source_runs r ON r.id=i.run_id WHERE i.id=?",(r,n)->{String id=WireValues.id(r.getString(1),false),run=WireValues.id(r.getString(2),false),source=WireValues.id(r.getString(3),true),key=r.getString(4),entity=r.getString(5),category=r.getString(6),state=r.getString(9),date=r.getString(12);if(key==null||!"news_tech".equals(category)||!"accepted".equals(r.getString(8))||!Set.of("pending","ready","analyzed","needs_review","excluded","failed").contains(state)||date==null)throw invalid();if(entity!=null)WireValues.id(entity,false);long revision=number(r.getObject(7)),valid=number(r.getObject(10));var created=r.getTimestamp(11,Calendar.getInstance(TimeZone.getTimeZone("UTC")));if(valid>1||created==null||created.getNanos()%1000!=0)throw invalid();LocalDate day=LocalDate.parse(date);if(!date.equals(day.toString())||!itemId.equals(id))throw invalid();return new Header(id,run,source,key,entity,day,revision,state,valid==1,created.toInstant());},itemId);if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();}
    private static long number(Object value){if(!(value instanceof Number))throw invalid();try{long n=new java.math.BigInteger(value.toString()).longValueExact();if(n<0||n>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();return n;}catch(RuntimeException bad){throw invalid();}}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("native entity count origin locator unavailable");}
}
