package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure comparison of complete original version evidence. A current marker grants no native execution permission. */
public final class CatalogPreviousVersionProfile {
    public static final class Profile {
        private final CatalogPreviousAnalysisEvidence.Evidence evidence;
        private final List<CatalogVersionEvidenceMarker.Marker> markers;
        private final String semanticHash,proofHash,proofJson;
        private Profile(CatalogPreviousAnalysisEvidence.Evidence evidence,List<CatalogVersionEvidenceMarker.Marker> markers){
            this.evidence=evidence;this.markers=List.copyOf(markers);var previous=evidence.previous();
            var semantic=JsonMapper.shared().createObjectNode().put("schema_version","catalog-previous-version-profile-v1").put("category",previous.category());
            semantic.set("identity",CanonicalJson.parse(previous.identityJson()));var hashes=semantic.putArray("version_hashes");markers.stream().map(CatalogVersionEvidenceMarker.Marker::hash).distinct().sorted().forEach(hashes::add);
            semanticHash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,semantic);
            var proof=JsonMapper.shared().createObjectNode().put("schema_version","catalog-previous-version-proof-v1").put("category",previous.category()).put("entity_id",previous.entityId()).put("root_id",previous.rootId()).put("generation",previous.generation()).put("input_hash",previous.inputHash()).put("result_hash",previous.resultHash()).put("semantic_hash",semanticHash);
            var members=proof.putArray("members");for(int n=0;n<markers.size();n++){var member=evidence.members().get(n);members.addObject().put("item_id",member.id()).put("body_hash",member.bodyHash()).put("version_hash",markers.get(n).hash());}
            proofJson=new String(CanonicalJson.bytes(proof),StandardCharsets.UTF_8);if(proofJson.getBytes(StandardCharsets.UTF_8).length>65536)throw invalid();proofHash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,proof);
        }
        public CatalogPreviousAnalysisEvidence.Evidence evidence(){return evidence;}
        public String semanticHash(){return semanticHash;}public String proofHash(){return proofHash;}public String proofJson(){return proofJson;}
        public List<CatalogVersionEvidenceMarker.Marker> markers(){return markers;}
        /** Unknown comparison domain stays empty. This is a pure fact, not a reanalysis decision. */
        public Optional<Boolean> changed(CatalogVersionEvidenceMarker.Marker current){
            if(current==null||!evidence.previous().category().equals(current.category())||!evidence.previous().identityJson().equals(current.identityJson()))throw invalid();
            String domain=domain(current);var comparable=markers.stream().filter(m->domain.equals(domain(m))).toList();
            if(comparable.isEmpty())return Optional.empty();
            return Optional.of(comparable.stream().noneMatch(m->m.hash().equals(current.hash())));
        }
        @Override public String toString(){return "CatalogPreviousVersionProfile[redacted]";}
    }
    private CatalogPreviousVersionProfile(){ }
    public static Optional<Profile> read(CatalogPreviousAnalysisEvidence.Evidence evidence){
        Objects.requireNonNull(evidence);var previous=evidence.previous();var markers=new ArrayList<CatalogVersionEvidenceMarker.Marker>();
        for(var member:evidence.members()){
            var marker=CatalogVersionEvidenceMarker.readCatalog(CanonicalJson.parse(member.payloadJson()));
            if(marker.isEmpty())return Optional.empty();var value=marker.orElseThrow();
            if(!previous.category().equals(value.category())||!previous.identityJson().equals(value.identityJson()))throw invalid();markers.add(value);
        }
        if(markers.isEmpty()||markers.size()!=evidence.members().size())throw invalid();
        return Optional.of(new Profile(evidence,markers));
    }
    private static String domain(CatalogVersionEvidenceMarker.Marker marker){
        JsonNode version=CanonicalJson.parse(marker.json()).get("version");String kind=WireValues.text(version.get("kind"));var scope=JsonMapper.shared().createObjectNode().put("kind",kind);
        switch(kind){
            case "release" -> { }
            case "skill_document" -> scope.set("path",version.get("path"));
            case "benchmark_result" -> {for(String field:List.of("benchmark","task","evaluation_scope","evaluation_config","provenance"))scope.set(field,version.get(field));}
            default -> throw invalid();
        }
        return new String(CanonicalJson.bytes(scope),StandardCharsets.UTF_8);
    }
    private static IllegalStateException invalid(){return new IllegalStateException("catalog previous version profile unavailable");}
}
