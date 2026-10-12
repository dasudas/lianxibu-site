package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.articles.ObservationPayloadPolicy;
import com.lianxibu.site.service.content.FrozenPublicMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Complete original selected evidence only. This does not authorize a new analysis or source admission. */
public final class CatalogPreviousAnalysisEvidence {
    public record Member(String id,String kind,String bodyHash,String payloadJson){
        @Override public String toString(){return "CatalogPreviousEvidenceMember[redacted]";}
    }
    public static final class Evidence {
        private final CatalogPreviousAnalysisService.Previous previous;
        private final List<Member> members;
        private Evidence(CatalogPreviousAnalysisService.Previous previous,List<Member> members){this.previous=previous;this.members=List.copyOf(members);}
        public CatalogPreviousAnalysisService.Previous previous(){return previous;}public List<Member> members(){return members;}
        @Override public String toString(){return "CatalogPreviousAnalysisEvidence[redacted]";}
    }
    private CatalogPreviousAnalysisEvidence(){ }
    /** Missing legacy metadata or partial coverage is unknown, never a negative version decision. */
    public static Optional<Evidence> read(CatalogPreviousAnalysisService.Previous previous){
        Objects.requireNonNull(previous);var snapshot=CanonicalJson.parse(previous.inputJson());
        if(!snapshot.has(FrozenPublicMetadata.FIELD))return Optional.empty();
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        FrozenPublicMetadata.read(snapshot.get(FrozenPublicMetadata.FIELD),context);
        if(!"1".equals(WireValues.text(snapshot.get("schema_version"))))throw invalid();
        String coverage=WireValues.text(snapshot.get("coverage_state"));
        var missing=snapshot.get("missing");if(missing==null||!missing.isArray())throw invalid();
        if("partial".equals(coverage))return Optional.empty();
        if(!"complete".equals(coverage)||!missing.isEmpty())throw invalid();
        Instant cutoff=Instant.parse(WireValues.text(snapshot.get("cutoff")));
        if(!cutoff.equals(previous.inputCutoff())||cutoff.isAfter(previous.appliedAt()))throw invalid();
        var values=snapshot.get("members");if(values==null||!values.isArray()||values.isEmpty()||values.size()>100)throw invalid();
        var input=context.input(previous.rootId(),"previous-evidence",previous.inputHash(),0);
        Map<String,Member> members=new TreeMap<>();
        for(var member:values){
            var fields=Set.of("id","kind","business_date","completed_at","body_hash","body","references");WireValues.object(member,fields,fields);
            String id=WireValues.id(member.get("id"),false),kind=WireValues.text(member.get("kind"));
            if(!Set.of("catalog","metric").contains(kind)||!input.itemIds().contains(id))throw invalid();
            java.time.LocalDate.parse(WireValues.text(member.get("business_date")));
            if(Instant.parse(WireValues.text(member.get("completed_at"))).isAfter(cutoff))throw invalid();
            JsonNode body=member.get("body");String hash=WireValues.sha256(member.get("body_hash"));
            if(!hash.equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body)))throw invalid();
            // These two closed payload types have no source-specific rule. No synthetic source ID is used.
            ObservationPayloadPolicy.validateStored(kind,body,null);
            var owner=kind.equals("metric")?body.get("subject"):body;
            if(!previous.category().equals(WireValues.text(owner.get("category")))
                    ||!previous.identityJson().equals(canonical(owner.get("identity"))))throw invalid();
            var refs=member.get("references");if(refs==null||!refs.isArray()||refs.size()>1000)throw invalid();
            var seen=new HashSet<String>();for(var ref:refs)if(!seen.add(WireValues.id(ref,false)))throw invalid();
            if(members.putIfAbsent(id,new Member(id,kind,hash,canonical(body)))!=null)throw invalid();
        }
        if(!members.keySet().equals(input.itemIds()))throw invalid();
        var referenced=new HashSet<String>();
        for(var target:context.referenceTargets()){
            if(!"catalog_evidence".equals(WireValues.text(target.get("kind"))))throw invalid();
            if(!referenced.add(WireValues.id(target.get("item_id"),false)))throw invalid();
        }
        if(!referenced.equals(members.keySet()))throw invalid();
        return Optional.of(new Evidence(previous,new ArrayList<>(members.values())));
    }
    private static String canonical(JsonNode value){return new String(CanonicalJson.bytes(value),StandardCharsets.UTF_8);}
    private static IllegalStateException invalid(){return new IllegalStateException("catalog original analysis evidence unavailable");}
}
