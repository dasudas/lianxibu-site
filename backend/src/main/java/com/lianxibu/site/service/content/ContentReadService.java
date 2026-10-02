package com.lianxibu.site.service.content;

import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.repository.content.JdbcContentReads.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Conservative compatibility view of current content; historical bodies are not synthesized. */
public final class ContentReadService {
    public interface ReferenceProof {
        /** Must verify the frozen dependency vector and current validity in this same read snapshot. */
        boolean valid(Row row);
    }
    public record Page(List<Row> items, long page, int pageSize, long total) { }
    /** Immutable service projection for cross-module orchestration. */
    public record CurrentVersion(String kind,String id,long revision,long bodyRevision,
                                 String inputJobId,byte[] bodyHash,String bodyJson,String state) {
        public CurrentVersion { bodyHash=bodyHash==null?null:bodyHash.clone(); }
        @Override public byte[] bodyHash(){return bodyHash==null?null:bodyHash.clone();}
    }
    private static final Set<String> KINDS = Set.of("report", "agent", "skill", "workflow", "model");
    private final JdbcContentReads store;
    private final TransactionTemplate reads;
    private final ReferenceProof references;

    public ContentReadService(JdbcContentReads store, PlatformTransactionManager tx,
                              ReferenceProof references) {
        if (store == null || tx == null || references == null) {
            throw new IllegalArgumentException("content read dependencies required");
        }
        this.store = store;
        this.references = references;
        reads = new TransactionTemplate(tx);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public static ReferenceProof denyUnverifiedReferences() { return row -> false; }

    public Optional<Row> adminCurrent(String kind, String id) {
        key(kind, id);
        return reads.execute(status -> Optional.ofNullable(store.current(kind, id)));
    }

    public Optional<CurrentVersion> administrativeVersion(String kind,String id) {
        return adminCurrent(kind,id).map(row -> new CurrentVersion(row.kind(),row.id(),
                row.revision(),row.effectiveBodyRevision(),row.inputJobId(),row.bodyHashV1(),row.bodyJson(),row.state()));
    }

    public Optional<Row> adminRevision(String kind, String id, long revision) {
        if (revision < 1) throw new IllegalArgumentException("invalid revision");
        // contents is a current-row table; equal current revision is the only provable body.
        return adminCurrent(kind, id).filter(row -> row.effectiveBodyRevision() == revision);
    }

    public Optional<Row> published(String kind, String id) {
        key(kind, id);
        return reads.execute(status -> {
            Row row = store.current(kind, id);
            return row != null && eligible(row) ? Optional.of(row) : Optional.empty();
        });
    }

    public Page publishedPage(String kind, long page, int size) {
        if (kind != null && !KINDS.contains(kind)) throw new IllegalArgumentException("invalid kind");
        if (page < 1 || size < 1 || size > 50 || page > Long.MAX_VALUE / size) {
            throw new IllegalArgumentException("invalid page");
        }
        return reads.execute(status -> {
            long start = (page - 1) * size;
            long visible = 0;
            List<Row> items = new ArrayList<>();
            String afterKind = "";
            String afterId = "";
            while (true) {
                List<Row> batch = store.publishedCandidates(kind, afterKind, afterId, 500);
                if (batch.isEmpty()) break;
                for (Row row : batch) {
                    if (eligible(row)) {
                        if (visible >= start && items.size() < size) items.add(row);
                        visible++;
                    }
                }
                Row last = batch.getLast();
                afterKind = last.kind();
                afterId = last.id();
                if (batch.size() < 500) break;
            }
            return new Page(List.copyOf(items), page, size, visible);
        });
    }

    private boolean eligible(Row row) {
        return "published".equals(row.state()) && row.publishedAt() != null
                && row.bodyRevision() != null && row.bodyHashV1() != null
                && row.reviewHashV1() != null && row.reviewDependencyStamp() != null
                && row.reviewStampHash() != null && row.reviewStampId() != null
                && row.reviewedInputHash() != null && row.inputJobId() != null
                && references.valid(row);
    }

    private static void key(String kind, String id) {
        if (!KINDS.contains(kind) || id == null || id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("invalid content key");
        }
    }
}
