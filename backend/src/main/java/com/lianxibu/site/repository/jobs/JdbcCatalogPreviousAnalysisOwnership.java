package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.WireValues;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native scalar associations only. The consumer must prove the original analysis and evidence. */
public final class JdbcCatalogPreviousAnalysisOwnership {
    public record Entity(String id,String category,String identity,byte[] identityHash,Long revision,Long generation,String state,Integer legacy,Long identityBytes){
        public Entity {identityHash=identityHash==null?null:identityHash.clone();}
        @Override public byte[] identityHash(){return identityHash==null?null:identityHash.clone();}
        @Override public String toString(){return "CatalogPreviousEntityHeader[redacted]";}
    }
    public record Content(String kind,String id,String entity,String inputRoot,byte[] bodyHash,Long revision,Long bodyRevision,String state){
        public Content {bodyHash=bodyHash==null?null:bodyHash.clone();}
        @Override public byte[] bodyHash(){return bodyHash==null?null:bodyHash.clone();}
        @Override public String toString(){return "CatalogPreviousContentHeader[redacted]";}
    }
    private final JdbcTemplate jdbc;
    public JdbcCatalogPreviousAnalysisOwnership(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
    public Entity entity(String id){read();return entity(id,false);}
    public Content content(String category,String id){read();return content(category,id,false);}
    public Entity lockedEntity(String id){writer();return entity(id,true);}
    public Content lockedContent(String category,String id){writer();return content(category,id,true);}
    private Entity entity(String id,boolean writer){
        WireValues.id(id,false);var rows=jdbc.query("SELECT id,category,CASE WHEN OCTET_LENGTH(identity_key) BETWEEN 1 AND 4096 THEN identity_key ELSE NULL END,identity_hash,revision,analysis_generation,analysis_state,legacy_flag,OCTET_LENGTH(identity_key) FROM analyzed_entities WHERE id=? LIMIT 2"+(writer?" FOR UPDATE":""),(r,n)->{
            Long bytes=r.getObject(9,Long.class);String identity=r.getString(3);if(bytes!=null&&(bytes<1||bytes>4096||identity==null))throw invalid();
            return new Entity(r.getString(1),r.getString(2),identity,r.getBytes(4),r.getObject(5,Long.class),r.getObject(6,Long.class),r.getString(7),r.getObject(8,Integer.class),bytes);
        },id);if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
    }
    private Content content(String category,String id,boolean writer){
        category(category);WireValues.id(id,false);var rows=jdbc.query("SELECT kind,id,entity_id,input_job_id,body_hash_v1,revision,body_revision,state FROM contents WHERE kind=? AND id=? LIMIT 2"+(writer?" FOR UPDATE":""),(r,n)->new Content(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBytes(5),r.getObject(6,Long.class),r.getObject(7,Long.class),r.getString(8)),category,id);if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
    }
    private static void category(String category){if(category==null||!Set.of("agent","skill","workflow","model").contains(category))throw invalid();}
    private static void read(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static void writer(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("catalog previous analysis ownership unavailable");}
}
