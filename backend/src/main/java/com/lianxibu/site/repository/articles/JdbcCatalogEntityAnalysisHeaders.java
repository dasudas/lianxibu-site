package com.lianxibu.site.repository.articles;
import com.lianxibu.site.domain.protocol.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Bounded native entity identity and current analysis state; values alone grant no analysis intent permission. */
public final class JdbcCatalogEntityAnalysisHeaders {
    public record Header(String id,String category,String identityKey,String identityHash,long revision,long generation,String state){ }
    public static final class CapacityRejected extends RuntimeException{public CapacityRejected(){super("native catalog entity header capacity unavailable");}}
    private final JdbcTemplate jdbc;private final String category;
    public JdbcCatalogEntityAnalysisHeaders(DataSource source,String category){jdbc=new JdbcTemplate(Objects.requireNonNull(source));this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};}
    public Map<String,Header> read(Set<String> ids){return query(ids,false);}
    public Map<String,Header> lock(Set<String> ids){return query(ids,true);}
    private Map<String,Header> query(Set<String> ids,boolean writer){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||writer&&TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!writer&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(writer?TransactionDefinition.ISOLATION_READ_COMMITTED:TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();
        Objects.requireNonNull(ids);if(ids.size()>100)throw new CapacityRejected();var expected=new TreeSet<String>();for(String id:ids)expected.add(WireValues.id(id,false));if(expected.isEmpty())return Map.of();
        String parameters=String.join(",",Collections.nCopies(expected.size(),"?"));
        var rows=jdbc.query("SELECT id,category,CASE WHEN OCTET_LENGTH(identity_key) BETWEEN 1 AND 4096 THEN identity_key ELSE NULL END,identity_hash,revision,analysis_generation,analysis_state,legacy_flag,OCTET_LENGTH(identity_key) FROM analyzed_entities WHERE id IN ("+parameters+") ORDER BY id LIMIT "+(expected.size()+1)+(writer?" FOR UPDATE":""),(r,n)->{
            String id=r.getString(1),kind=r.getString(2),identity=r.getString(3),state=r.getString(7);byte[] hash=r.getBytes(4);Long revision=r.getObject(5,Long.class),generation=r.getObject(6,Long.class),size=r.getObject(9,Long.class);Integer legacy=r.getObject(8,Integer.class);
            if(size!=null&&size>4096)throw new CapacityRejected();
            if(!expected.contains(id)||!category.equals(kind)||identity==null||identity.isBlank()||size==null||size<1||hash==null||hash.length!=32||!Arrays.equals(hash,JdbcEntityDedup.identityHash(category,identity))||revision==null||revision<0||revision>=CanonicalJson.MAX_SAFE_INTEGER||generation==null||generation<0||generation>CanonicalJson.MAX_SAFE_INTEGER||legacy==null||legacy!=0||state==null||!Set.of("pending","queued","analyzed","failed","needs_review").contains(state)||"pending".equals(state)!=(generation==0)&&!"needs_review".equals(state))throw invalid();
            return new Header(id,kind,identity,HexFormat.of().formatHex(hash),revision,generation,state);
        },expected.toArray());
        var found=new HashMap<String,Header>();for(var row:rows)if(found.putIfAbsent(row.id(),row)!=null)throw invalid();if(!found.keySet().equals(expected))throw invalid();return Map.copyOf(found);
    }
    private static IllegalStateException invalid(){return new IllegalStateException("native catalog entity headers unavailable");}
}
