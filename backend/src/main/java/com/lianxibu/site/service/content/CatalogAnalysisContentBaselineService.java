package com.lianxibu.site.service.content;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.content.JdbcCatalogAnalysisContentBaselines;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Content-owned full baseline; caller must separately prove original day and entity allocation. */
public final class CatalogAnalysisContentBaselineService {
    public record Baseline(String category,String id,String entityId,long revision,long bodyRevision,String inputRoot,String bodyHash,String bodyJson,String versionJson,String state){@Override public String toString(){return "CatalogAnalysisTarget[redacted]";}}
    private final JdbcCatalogAnalysisContentBaselines storage;private final TransactionTemplate reads;
    public CatalogAnalysisContentBaselineService(DataSource source,PlatformTransactionManager manager,String category){storage=new JdbcCatalogAnalysisContentBaselines(Objects.requireNonNull(source),category);reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public Baseline find(String entity,String target){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();return reads.execute(s->findInSnapshot(entity,target));}
    public Baseline findInSnapshot(String entity,String target){return convert(storage.read(entity,target));}
    public Baseline lockInWriter(String entity,String target){return convert(storage.lock(entity,target));}
    public static String newContentId(String category,String entity){if(!Set.of("agent","skill","workflow","model").contains(category))throw invalid();WireValues.id(entity,false);var key=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("purpose","catalog-analysis-content-v1").put("category",category).put("entity_id",entity);return "catalog-content-"+com.lianxibu.site.domain.protocol.CanonicalJson.hash(com.lianxibu.site.domain.protocol.CanonicalJson.Domain.IDENTITY,key).substring(0,48);}
    private static Baseline convert(JdbcCatalogAnalysisContentBaselines.Row row){return row==null?null:new Baseline(row.category(),row.id(),row.entityId(),row.revision(),row.bodyRevision(),row.inputRoot(),row.bodyHash(),row.bodyJson(),row.versionJson(),row.state());}
    private static IllegalStateException invalid(){return new IllegalStateException("catalog analysis content observation requires its own snapshot");}
}
