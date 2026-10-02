package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.articles.JdbcEvidenceValidity;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Narrow evidence validity port; permanent disposition chains are owned by jobs. */
public final class EvidenceValidityService {
    public record State(long revision, boolean valid) { }
    private record Target(String kind,String id) { }
    private final JdbcEvidenceValidity store;
    public EvidenceValidityService(JdbcEvidenceValidity store){this.store=Objects.requireNonNull(store);}
    public Optional<State> read(JsonNode key){var target=target(key);var row=store.read(target.kind(),target.id());return row==null?Optional.empty():Optional.of(new State(row.revision(),row.valid()));}
    public void lock(JsonNode key){var target=target(key);store.lock(target.kind(),target.id());}
    public void update(JsonNode key,long expected,long next,boolean valid){var target=target(key);store.update(target.kind(),target.id(),expected,next,valid);}
    private static Target target(JsonNode node){
        if(node==null || !node.isObject())throw new IllegalArgumentException("evidence key required");
        String kind=WireValues.text(node.get("kind"));String field=switch(kind){case "article_revision"->"id";case "catalog_evidence"->"item_id";default->throw new IllegalArgumentException("invalid evidence kind");};
        WireValues.object(node,Set.of("kind",field),Set.of("kind",field));return new Target(kind,WireValues.id(node.get(field),false));
    }
}
