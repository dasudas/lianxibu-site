package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.*;
import java.time.Clock;
import java.util.*;

/** Freezes source-owned original facts; current AI eligibility remains a separate proof. */
public final class FrozenSourceUniverseService {
    private final JdbcFrozenSourceUniverses storage;private final MemberUniverse.Reader originals;private final Clock clock;
    public FrozenSourceUniverseService(JdbcFrozenSourceUniverses storage,MemberUniverse.Reader originals,Clock clock) {
        this.storage=Objects.requireNonNull(storage);this.originals=Objects.requireNonNull(originals);this.clock=Objects.requireNonNull(clock);
    }
    public FrozenSourceUniverse.Reference freeze(String owner,String runId) {
        try {
            JdbcFrozenInputs.requireTx();WireValues.id(owner,false);WireValues.id(runId,false);storage.lockOwner(owner);
            var original=originals.read(runId);if(original==null||!runId.equals(original.runId()))throw unavailable();
            var encoded=FrozenSourceUniverse.encode(owner,original);String stamp=stamp(runId);var previous=storage.read(owner,stamp);
            if(!previous.isEmpty()) {
                if(!original.equals(decode(encoded.reference(),previous)))throw unavailable();return encoded.reference();
            }
            var now=clock.instant();
            for(int n=0;n<encoded.payloads().size();n++)storage.put(owner,stamp,n,encoded.payloads().get(n),now);
            verifyWritten(encoded,stamp);return encoded.reference();
        }catch(RuntimeException rejected){throw unavailable();}
    }
    /** Verify the exact already-validated encoding; committed reads still decode the entire archive. */
    private void verifyWritten(FrozenSourceUniverse.Encoded encoded,String stamp) {
        String owner=encoded.reference().ownerId();var parts=storage.read(owner,stamp);
        if(parts.size()!=encoded.payloads().size())throw unavailable();
        for(int n=0;n<parts.size();n++) {
            var part=parts.get(n);var body=CanonicalJson.parse(part.payload());
            if(!owner.equals(part.owner())||!"input".equals(part.purpose())||!stamp.equals(part.stamp())||part.number()!=n
                    ||!JdbcFrozenSourceUniverses.partId(owner,stamp,n).equals(part.id())
                    ||!encoded.payloads().get(n).equals(JdbcFrozenInputs.canonical(body))
                    ||!Arrays.equals(part.hash(),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,body))))throw unavailable();
        }
    }
    public MemberUniverse.Universe read(FrozenSourceUniverse.Reference reference) {
        try {
            JdbcFrozenInputs.requireTx();Objects.requireNonNull(reference);storage.lockOwner(reference.ownerId());
            return decode(reference,storage.read(reference.ownerId(),stamp(reference.runId())));
        }catch(RuntimeException rejected){throw unavailable();}
    }
    /** Reads only already committed original blocks in the caller's active transaction. */
    public MemberUniverse.Universe readImmutable(FrozenSourceUniverse.Reference reference) {
        try {
            Objects.requireNonNull(reference);storage.immutableOwner(reference.ownerId());
            return decode(reference,storage.immutableParts(reference.ownerId(),stamp(reference.runId())));
        }catch(RuntimeException rejected){throw unavailable();}
    }
    private MemberUniverse.Universe decode(FrozenSourceUniverse.Reference reference,List<JdbcFrozenSourceUniverses.Part> parts) {
        if(parts.size()<2||parts.size()>FrozenSourceUniverse.MAX_CHUNKS+1)throw unavailable();
        String stamp=stamp(reference.runId());var payloads=new ArrayList<String>();
        for(int n=0;n<parts.size();n++) {
            var part=parts.get(n);var body=CanonicalJson.parse(part.payload());
            if(!reference.ownerId().equals(part.owner())||!"input".equals(part.purpose())||!stamp.equals(part.stamp())||part.number()!=n
                    ||!JdbcFrozenSourceUniverses.partId(reference.ownerId(),stamp,n).equals(part.id())
                    ||!Arrays.equals(part.hash(),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,body))))throw unavailable();
            payloads.add(JdbcFrozenInputs.canonical(body));
        }
        return FrozenSourceUniverse.decode(reference,payloads);
    }
    private static String stamp(String run){return JdbcFrozenInputs.stableId("source-universe",run);}
    private static IllegalStateException unavailable(){return new IllegalStateException("frozen source universe proof unavailable");}
}
