package com.lianxibu.site.repository.content;
import com.lianxibu.site.domain.protocol.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Complete bounded native analysis targets. Lookup does not grant generation or input authority. */
public final class JdbcCatalogAnalysisContentBaselines {
    public record Row(String category,String id,String entityId,long revision,long bodyRevision,String inputRoot,String bodyHash,String bodyJson,String versionJson,String state){@Override public String toString(){return "CatalogAnalysisContentBaseline[redacted]";}}
    private final JdbcTemplate jdbc;private final String category;
    public JdbcCatalogAnalysisContentBaselines(DataSource source,String category){jdbc=new JdbcTemplate(Objects.requireNonNull(source));this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};}
    public Row read(String entity,String target){transaction(true);return find(entity,target,false);}
    public Row lock(String entity,String target){transaction(false);return find(entity,target,true);}
    private Row find(String entity,String target,boolean lock){
        WireValues.id(entity,false);WireValues.id(target,false);
        var values=jdbc.query("SELECT kind,id,entity_id,revision,body_revision,input_job_id,body_hash_v1,CASE WHEN OCTET_LENGTH(body_json) BETWEEN 1 AND 262144 THEN body_json ELSE NULL END,CASE WHEN OCTET_LENGTH(content_key_json) BETWEEN 1 AND 16384 THEN content_key_json ELSE NULL END,state FROM contents WHERE (kind=? AND entity_id=?) OR id=? ORDER BY kind,id LIMIT 3"+(lock?" FOR UPDATE":""),(r,n)->{
            String kind=r.getString(1),id=WireValues.id(r.getString(2),false),owner=WireValues.id(r.getString(3),false),root=WireValues.id(r.getString(6),false),body=r.getString(8),key=r.getString(9),state=r.getString(10);Long revision=r.getObject(4,Long.class),bodyRevision=r.getObject(5,Long.class);byte[] hash=r.getBytes(7);
            if(!category.equals(kind)||!target.equals(id)||!entity.equals(owner)||revision==null||revision<1||revision>=CanonicalJson.MAX_SAFE_INTEGER||bodyRevision==null||bodyRevision<1||bodyRevision>revision||hash==null||hash.length!=32||body==null||key==null||state==null||!Set.of("draft","published").contains(state))throw invalid();
            var json=CanonicalJson.parse(body);String digest=HexFormat.of().formatHex(hash);var version=ContentVersionKey.fromJson(CanonicalJson.parse(key));if(!digest.equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,json))||!category.equals(version.contentKind())||!target.equals(version.contentId())||bodyRevision!=version.revision()||!digest.equals(version.bodyHash())||!root.equals(version.inputJobId())||!category.equals(WireValues.text(json.get("category")))||!entity.equals(WireValues.id(json.get("entity_id"),false)))throw invalid();
            return new Row(kind,id,owner,revision,bodyRevision,root,digest,canonical(json),canonical(CanonicalJson.parse(key)),state);
        },category,entity,target);if(values.size()>1)throw invalid();return values.isEmpty()?null:values.getFirst();
    }
    private static String canonical(tools.jackson.databind.JsonNode n){return new String(CanonicalJson.bytes(n),java.nio.charset.StandardCharsets.UTF_8);}
    private static void transaction(boolean read){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()!=read||!Integer.valueOf(read?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("native catalog analysis content baseline unavailable");}
}
