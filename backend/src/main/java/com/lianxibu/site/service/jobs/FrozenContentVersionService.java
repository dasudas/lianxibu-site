package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenContentVersions;
import java.util.*;
import tools.jackson.databind.JsonNode;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Validates an immutable body proof before exposing its original frozen context. */
public final class FrozenContentVersionService {
    public record Proven(ContentVersionKey version,FrozenApplicationContext context,
                         String inputHash,String bodyJson,String snapshotJson,java.time.Instant availableAt) {
        public Proven(ContentVersionKey version,FrozenApplicationContext context,String inputHash,String bodyJson,String snapshotJson){this(version,context,inputHash,bodyJson,snapshotJson,null);}
    }
    private final JdbcFrozenContentVersions store;
    public FrozenContentVersionService(JdbcFrozenContentVersions store){this.store=Objects.requireNonNull(store);}
    public Optional<Proven> find(ContentVersionKey target){
        if(target==null||target.revision()<1||target.inputJobId()==null)return Optional.empty();
        var frame=snapshot();var retained=frame==null?null:frame.values.get(target);
        if(retained!=null)return Optional.of(observed(retained));
        var proof=store.read(target);if(proof==null)return Optional.empty();
        if(!target.equals(ContentVersionKey.fromJson(CanonicalJson.parse(proof.versionJson()))))throw unavailable();
        var snapshot=CanonicalJson.parse(proof.snapshotJson());
        if(!proof.inputHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot)))throw unavailable();
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        if(!target.contentKind().equals(context.contentKind())||!target.contentId().equals(context.contentId()))throw unavailable();JsonNode body;
        if(proof.resultJson()!=null){var result=ResultApplicationService.validate(context.input(target.inputJobId(),proof.attemptId(),proof.inputHash(),0),proof.resultJson());body=CanonicalJson.parse(result.payloadJson()).get("body");}else body=CanonicalJson.parse(proof.bodyJson());
        if(body==null||!body.isObject()||!target.bodyHash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body)))throw unavailable();
        var immutable=new Proven(target,context,proof.inputHash(),new String(CanonicalJson.bytes(body),java.nio.charset.StandardCharsets.UTF_8),new String(CanonicalJson.bytes(snapshot),java.nio.charset.StandardCharsets.UTF_8));
        var result=observed(immutable);
        if(frame!=null)frame.remember(target,immutable);
        return Optional.of(result);
    }
    private Proven observed(Proven proof){
        return new Proven(proof.version(),proof.context(),proof.inputHash(),proof.bodyJson(),proof.snapshotJson(),store.observedAt(proof.version(),proof.inputHash()));
    }
    private ValidatedReadSnapshot snapshot(){
        if(!store.canReuseImmutableProofInCurrentReadSnapshot())return null;
        for(var item:TransactionSynchronizationManager.getSynchronizations())
            if(item instanceof ValidatedReadSnapshot frame&&frame.issuer==this)return frame;
        var frame=new ValidatedReadSnapshot(this);TransactionSynchronizationManager.registerSynchronization(frame);return frame;
    }
    /** Keeps only successful immutable proof values in this issuer's actual read snapshot. */
    private static final class ValidatedReadSnapshot implements TransactionSynchronization {
        private final FrozenContentVersionService issuer;
        private final Map<ContentVersionKey,Proven> values=new LinkedHashMap<>();
        private long bytes;
        private ValidatedReadSnapshot(FrozenContentVersionService issuer){this.issuer=issuer;}
        private void remember(ContentVersionKey key,Proven proof){
            long size=16384L+text(key.contentKind())+text(key.contentId())+text(key.bodyHash())+text(key.inputJobId())
                    +text(proof.inputHash())+text(proof.bodyJson())+text(proof.snapshotJson())+text(proof.context().json());
            if(values.size()>=8||size>1024*1024||bytes+size>1024*1024)return;
            values.put(key,proof);bytes+=size;
        }
        private static long text(String value){return value==null?0:128L+8L*value.length();}
        @Override public void afterCompletion(int status){values.clear();bytes=0;}
    }
    public Optional<Proven> findAt(ContentVersionKey target,java.time.Instant cutoff){
        if(cutoff==null||cutoff.getNano()%1000!=0)throw unavailable();
        return find(target).filter(value->value.availableAt()!=null&&!value.availableAt().isAfter(cutoff));
    }
    public FrozenReferenceClosure.Evidence resolve(ContentVersionKey target){return find(target).map(p->new FrozenReferenceClosure.Evidence(p.version(),p.context())).orElse(null);}
    private static IllegalStateException unavailable(){return new IllegalStateException("immutable content version proof unavailable");}
}
