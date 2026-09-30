package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.sources.JdbcSourceReads;
import com.lianxibu.site.repository.sources.JdbcSourceReads.Row;
import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Legacy configuration stays internal; old enabled never grants production eligibility. */
public final class SourceReadService {
    private final JdbcSourceReads store;
    private final TransactionTemplate reads;
    private final Clock clock;

    public SourceReadService(JdbcSourceReads store, PlatformTransactionManager tx, Clock clock) {
        if (store == null || tx == null || clock == null) {
            throw new IllegalArgumentException("source read dependencies required");
        }
        this.store = store;
        this.clock = clock;
        reads = new TransactionTemplate(tx);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Optional<Row> internalSource(String id) {
        if (id == null || id.isBlank() || id.length() > 80) {
            throw new IllegalArgumentException("invalid source id");
        }
        return reads.execute(status -> Optional.ofNullable(store.byId(id)));
    }

    public JdbcSourceReads.Page internalPage(long page, int size) {
        return reads.execute(status -> store.page(page, size));
    }

    public boolean productionEligible(Row row) {
        return row != null && "production".equals(row.activationStage())
                && row.effectiveConfigJson() != null && row.configRevision() > 0
                && row.configValidUntil() != null && row.configValidUntil().isAfter(clock.instant());
    }

    /** A legacy React blog feed is a distinct source from the desired Releases endpoint. */
    public static boolean isReactReleasesEndpoint(Row row) {
        if (row == null) return false;
        JsonNode config = CanonicalJson.parse(row.legacyConfigJson());
        if (!config.isObject()) return false;
        JsonNode url = config.get("url");
        if (url == null || !url.isTextual()) url = config.get("entry_url");
        if (url == null || !url.isTextual()) return false;
        try {
            URI parsed = URI.create(url.asText());
            return "https".equalsIgnoreCase(parsed.getScheme())
                    && "api.github.com".equalsIgnoreCase(parsed.getHost())
                    && "/repos/facebook/react/releases".equals(parsed.getPath())
                    && parsed.getUserInfo() == null;
        } catch (IllegalArgumentException badUrl) {
            return false;
        }
    }
}
