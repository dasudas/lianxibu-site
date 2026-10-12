package com.lianxibu.site.service.articles;

import com.lianxibu.site.repository.articles.JdbcCatalogCurrentAnalysisContents;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Explicit articles-owned native content lookup. A locator never grants version or publication eligibility. */
public final class CatalogCurrentAnalysisContentService {
    private final JdbcCatalogCurrentAnalysisContents storage;
    private final TransactionTemplate reads;
    public CatalogCurrentAnalysisContentService(DataSource source,PlatformTransactionManager manager,String category){
        storage=new JdbcCatalogCurrentAnalysisContents(Objects.requireNonNull(source),category);reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Map<String,String> find(Set<String> entities){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("catalog locator requires its own snapshot");return Objects.requireNonNull(reads.execute(s->findInSnapshot(entities)));
    }
    public Map<String,String> findInSnapshot(Set<String> entities){try{return storage.read(entities);}catch(JdbcCatalogCurrentAnalysisContents.CapacityRejected guarded){throw new CapacityRejected();}}
    public static final class CapacityRejected extends RuntimeException {public CapacityRejected(){super("catalog locator capacity rejected");}}
}
