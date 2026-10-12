package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact bounded original catalog v2 INPUT. This provider grants no worker/result authority. */
public final class JdbcCatalogVersionFrozenInputs {
    public static final class CapacityRejected extends IllegalStateException {public CapacityRejected(){super("catalog version original input capacity unavailable");}}
    public record Snapshot(String json,String key,long generation,Instant cutoff){@Override public String toString(){return "OriginalCatalogVersionInput[redacted]";}}
    private final JdbcTemplate jdbc;private final JdbcFrozenInputs inputs;private final String category;
    public JdbcCatalogVersionFrozenInputs(DataSource source,String category){jdbc=new JdbcTemplate(Objects.requireNonNull(source));inputs=new JdbcFrozenInputs(source);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};}
    public Snapshot read(String root,String hash){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();WireValues.id(root,false);if(hash==null||!hash.matches("[0-9a-f]{64}"))throw invalid();
        var rows=jdbc.query("SELECT id,record_kind,task_type,generation,input_hash,CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END,logical_key_hash,input_cutoff_at,OCTET_LENGTH(logical_key) FROM jobs WHERE id=? LIMIT 2",(r,n)->{var time=r.getTimestamp(8,Calendar.getInstance(TimeZone.getTimeZone("UTC")));return new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getObject(4,Long.class),r.getBytes(5),r.getString(6),r.getBytes(7),time==null?null:time.toInstant(),r.getObject(9,Long.class)};},root);
        if(rows.size()!=1)throw invalid();var row=rows.getFirst();if(!root.equals(row[0])||!"logical_task".equals(row[1])||!(category+"_dedup").equals(row[2])||row[3]==null||(long)row[3]<1||(long)row[3]>CanonicalJson.MAX_SAFE_INTEGER||!Arrays.equals(HexFormat.of().parseHex(hash),(byte[])row[4])||row[8]==null||(long)row[8]<1||(long)row[8]>4096||row[5]==null||!Arrays.equals(JdbcGenerationLineage.hash((String)row[5]),(byte[])row[6])||row[7]==null||((Instant)row[7]).getNano()%1000!=0)throw invalid();
        String json=inputs.read(root,hash);if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2097152)throw new CapacityRejected();var snapshot=CanonicalJson.parse(json);if(!hash.equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot))||!row[5].equals(WireValues.text(snapshot.get("logical_key")))||!row[2].equals(WireValues.text(snapshot.get("stage")))||(long)row[3]!=WireValues.nonnegativeInteger(snapshot.get("generation"))||!row[7].equals(WireValues.utcMicroseconds(snapshot.get("cutoff"))))throw invalid();return new Snapshot(json,(String)row[5],(long)row[3],(Instant)row[7]);
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog version input storage unavailable");}
}
