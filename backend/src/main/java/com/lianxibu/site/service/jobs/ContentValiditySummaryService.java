package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.ContentVersionKey;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Ledger facts for an exact body and its complete immutable reference closure. */
public final class ContentValiditySummaryService {
    public record Summary(boolean effectiveValid,boolean hasValidityHistory,int targetCount) { }
    private final FrozenContentVersionService versions;
    private final VersionValidityLedgerService ledger;
    public ContentValiditySummaryService(FrozenContentVersionService versions,VersionValidityLedgerService ledger) {
        this.versions=Objects.requireNonNull(versions);this.ledger=Objects.requireNonNull(ledger);
    }
    /** Caller owns the same consistent read transaction used for public qualification. */
    public Summary read(ContentVersionKey target) {
        var proven=versions.find(target).orElseThrow(()->new IllegalStateException("content validity proof unavailable"));
        var closure=new FrozenReferenceClosure(new FrozenReferenceClosure.Source(){
            public FrozenReferenceClosure.Evidence resolve(ContentVersionKey key){return versions.resolve(key);}
            public boolean leafExists(JsonNode leaf){return ledger.exactVersionExists(leaf);}
        }).expand(target,proven.context());
        var own=ledger.validity(FrozenReferenceClosure.version(target));
        boolean valid=own.effectiveValid(),history=own.revision()>0;
        for(var reference:closure.targets()) {
            var state=ledger.validity(reference);valid&=state.effectiveValid();history|=state.revision()>0;
        }
        return new Summary(valid,history,closure.targets().size()+1);
    }
}
