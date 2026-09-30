package com.lianxibu.site.service.articles;

import com.lianxibu.site.repository.articles.JdbcArticleReads;
import com.lianxibu.site.repository.articles.JdbcArticleReads.ArticleRow;
import com.lianxibu.site.repository.articles.JdbcArticleReads.EntityRow;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Private read core; identity and personal record composition belong to later tasks. */
public final class ArticleReadService {
    private final JdbcArticleReads store;
    private final TransactionTemplate reads;

    public ArticleReadService(JdbcArticleReads store, PlatformTransactionManager tx) {
        if (store == null || tx == null) throw new IllegalArgumentException("article read dependencies required");
        this.store = store;
        reads = new TransactionTemplate(tx);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Optional<ArticleRow> article(String id) {
        key(id);
        return reads.execute(status -> Optional.ofNullable(store.article(id)));
    }

    public JdbcArticleReads.Page<ArticleRow> articles(String sourceId, String titleQuery,
                                                       long page, int pageSize) {
        if (sourceId != null) key(sourceId);
        query(titleQuery);
        return reads.execute(status -> store.articles(sourceId, titleQuery, page, pageSize));
    }

    public Optional<EntityRow> entity(String id) {
        key(id);
        return reads.execute(status -> Optional.ofNullable(store.entity(id)));
    }

    public JdbcArticleReads.Page<EntityRow> entities(String category, String titleQuery,
                                                      long page, int pageSize) {
        if (category != null && !java.util.Set.of("news_tech", "agent", "skill", "workflow", "model")
                .contains(category)) throw new IllegalArgumentException("invalid entity category");
        query(titleQuery);
        return reads.execute(status -> store.entities(category, titleQuery, page, pageSize));
    }

    private static void key(String value) {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw new IllegalArgumentException("invalid key");
        }
    }
    private static void query(String value) {
        if (value != null && value.length() > 256) throw new IllegalArgumentException("invalid query");
    }
}
