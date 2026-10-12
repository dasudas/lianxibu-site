package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Complete cutoff locators in one RR snapshot. Current counts are never historical count authority. */
public final class JdbcEntityHeatCutoffUniverse {
    public record Head(String id,String category,long revision,Long currentCount,Instant firstRecordedAt,boolean legacy){@Override public String toString(){return "NativeEntityHeatHead[redacted]";}}
    public record Page<T>(List<T> values,boolean hasMore){public Page{values=List.copyOf(values);}@Override public String toString(){return "EntityHeatCutoffPage[redacted]";}}
    private final JdbcTemplate sql;
    public JdbcEntityHeatCutoffUniverse(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));}
    public Page<Head> heads(String after,Instant cutoff,int limit){guard(after,cutoff,limit,false);var rows=sql.query(c->{var q=c.prepareStatement("SELECT id,category,revision,occurrence_count,first_recorded_at,legacy_flag FROM analyzed_entities WHERE id>? AND (first_recorded_at IS NULL OR first_recorded_at<=?) ORDER BY id LIMIT ?");q.setString(1,after);q.setTimestamp(2,java.sql.Timestamp.from(cutoff),utc());q.setInt(3,limit+1);return q;},(r,n)->{String id=WireValues.id(r.getString(1),false),category=r.getString(2);if(!Set.of("news_tech","agent","skill","workflow","model").contains(category))throw invalid();long revision=number(r.getObject(3));Long count=r.getObject(4)==null?null:number(r.getObject(4));Instant first=instant(r.getTimestamp(5,utc()));long legacy=number(r.getObject(6));if(legacy>1||first!=null&&first.isAfter(cutoff))throw invalid();return new Head(id,category,revision,count,first,legacy==1);});String previous=after;for(var row:rows){if(row.id().compareTo(previous)<=0)throw invalid();previous=row.id();}return new Page<>(rows.subList(0,Math.min(limit,rows.size())),rows.size()>limit);}
    public Page<String> events(String after,Instant cutoff,int limit){guard(after,cutoff,limit,true);var rows=sql.query(c->{var q=c.prepareStatement("SELECT id FROM jobs WHERE record_kind='disposition' AND target_type='entity_decision' AND id>? AND (acted_at IS NULL OR acted_at<=?) ORDER BY id LIMIT ?");q.setString(1,after);q.setTimestamp(2,java.sql.Timestamp.from(cutoff),utc());q.setInt(3,limit+1);return q;},(r,n)->r.getString(1));String previous=after;for(String id:rows){if(id==null||!id.matches("[0-9a-f]{64}")||id.compareTo(previous)<=0)throw invalid();previous=id;}return new Page<>(rows.subList(0,Math.min(limit,rows.size())),rows.size()>limit);}
    private static void guard(String after,Instant cutoff,int limit,boolean event){readonly();if(after==null||cutoff==null||cutoff.getNano()%1000!=0||limit<1||limit>64)throw invalid();if(!after.isEmpty()){if(event){if(!after.matches("[0-9a-f]{64}"))throw invalid();}else WireValues.id(after,false);}}
    private static long number(Object value){if(!(value instanceof Number))throw invalid();try{long n=new java.math.BigInteger(value.toString()).longValueExact();if(n<0||n>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();return n;}catch(RuntimeException e){throw invalid();}}
    private static Instant instant(java.sql.Timestamp value){if(value==null)return null;if(value.getNanos()%1000!=0)throw invalid();return value.toInstant();}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("complete native entity heat cutoff universe unavailable");}
}
