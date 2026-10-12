package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenContentVersions;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Validates an immutable body proof before exposing its original frozen context. */
public final class FrozenContentVersionService {
    public record Proven(ContentVersionKey version,FrozenApplicationContext context,
                         String inputHash,String bodyJson,String snapshotJson,java.time.Instant availableAt) {
        public Proven(ContentVersionKey version,FrozenApplicationContext context,String inputHash,String bodyJson,String snapshotJson){this(version,context,inputHash,bodyJson,snapshotJson,null);}
    }
    private final JdbcFrozenContentVersions store;
    public FrozenContentVersionService(JdbcFrozenContentVersions store){this.store=Objects.requireNonNull(store);}
    public Optional<Proven> find(ContentVersionKey target){
        if(target==null||target.revision()<1||target.inputJobId()==null)return Optional.empty();var proof=store.read(target);if(proof==null)return Optional.empty();
        if(!target.equals(ContentVersionKey.fromJson(CanonicalJson.parse(proof.versionJson()))))throw unavailable();
        var snapshot=CanonicalJson.parse(proof.snapshotJson());
        if(!proof.inputHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot)))throw unavailable();
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        if(!target.contentKind().equals(context.contentKind())||!target.contentId().equals(context.contentId()))throw unavailable();JsonNode body;
        if(proof.resultJson()!=null){var result=ResultApplicationService.validate(context.input(target.inputJobId(),proof.attemptId(),proof.inputHash(),0),proof.resultJson());body=CanonicalJson.parse(result.payloadJson()).get("body");}else body=CanonicalJson.parse(proof.bodyJson());
        if(body==null||!body.isObject()||!target.bodyHash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body)))throw unavailable();
        return Optional.of(new Proven(target,context,proof.inputHash(),new String(CanonicalJson.bytes(body),java.nio.charset.StandardCharsets.UTF_8),new String(CanonicalJson.bytes(snapshot),java.nio.charset.StandardCharsets.UTF_8),store.observedAt(target,proof.inputHash())));
    }
    public Optional<Proven> findAt(ContentVersionKey target,java.time.Instant cutoff){
        if(cutoff==null||cutoff.getNano()%1000!=0)throw unavailable();
        return find(target).filter(value->value.availableAt()!=null&&!value.availableAt().isAfter(cutoff));
    }
    public FrozenReferenceClosure.Evidence resolve(ContentVersionKey target){return find(target).map(p->new FrozenReferenceClosure.Evidence(p.version(),p.context())).orElse(null);}
    private static IllegalStateException unavailable(){return new IllegalStateException("immutable content version proof unavailable");}
}
