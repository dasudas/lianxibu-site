package com.lianxibu.site.service.articles;
import com.lianxibu.site.repository.articles.JdbcCatalogEntityAnalysisHeaders;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Articles-owned identity/state reads. Original day eligibility and generation intent remain separate proofs. */
public final class CatalogEntityAnalysisHeaderService {
    public record Header(String id,String category,String identityKey,String identityHash,long revision,long generation,String state){ }
    public static final class CapacityRejected extends RuntimeException{public CapacityRejected(){super("native catalog entity header capacity unavailable");}}
    private final JdbcCatalogEntityAnalysisHeaders storage;private final TransactionTemplate reads;
    public CatalogEntityAnalysisHeaderService(DataSource source,PlatformTransactionManager manager,String category){storage=new JdbcCatalogEntityAnalysisHeaders(Objects.requireNonNull(source),category);reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public Map<String,Header> find(Set<String> ids){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();return Objects.requireNonNull(reads.execute(s->findInSnapshot(ids)));}
    public Map<String,Header> findInSnapshot(Set<String> ids){try{return convert(storage.read(ids));}catch(JdbcCatalogEntityAnalysisHeaders.CapacityRejected guarded){throw new CapacityRejected();}}
    public Map<String,Header> lockInWriter(Set<String> ids){try{return convert(storage.lock(ids));}catch(JdbcCatalogEntityAnalysisHeaders.CapacityRejected guarded){throw new CapacityRejected();}}
    private static Map<String,Header> convert(Map<String,JdbcCatalogEntityAnalysisHeaders.Header> rows){var values=new HashMap<String,Header>();rows.forEach((id,row)->values.put(id,new Header(row.id(),row.category(),row.identityKey(),row.identityHash(),row.revision(),row.generation(),row.state())));return Map.copyOf(values);}
    private static IllegalStateException invalid(){return new IllegalStateException("catalog entity header observation requires its own snapshot");}
}
