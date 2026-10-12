package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.articles.CatalogVersionQualificationService.Selection;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Original native read facts only. A bounded projection and its business key grant no writer permission. */
public final class CatalogVersionQualificationProjection {
    public static final int MAX_BYTES=128*1024;
    public static final class Projection {
        private final String json,hash,judgmentKey,category,entity,content,root,member,oldProof,newProof,claimTargetHash;
        private final boolean changed;
        private Projection(Selection selected){
            var previous=selected.previous().evidence().previous();category=previous.category();entity=previous.entityId();content=previous.contentId();root=previous.rootId();member=selected.member().id();changed=selected.versionChanged();oldProof=selected.previous().proofHash();newProof=selected.currentProofHash();claimTargetHash=previous.claimTargetHash();
            var key=JsonMapper.shared().createObjectNode().put("schema_version","catalog-version-judgment-key-v1").put("category",category).put("entity_id",entity).put("version_hash",selected.current().hash()).put("qualification_hash",selected.qualificationHash());judgmentKey=CanonicalJson.hash(CanonicalJson.Domain.INPUT,key);
            var value=JsonMapper.shared().createObjectNode().put("schema_version","catalog-version-qualification-projection-v1").put("category",category).put("entity_id",entity).put("content_id",content).put("root_id",root).put("item_id",member).put("version_changed",changed).put("judgment_key",judgmentKey).put("claim_target_hash",claimTargetHash)
                .put("entity_revision",previous.entityRevision()).put("entity_generation",previous.entityGeneration()).put("content_revision",previous.contentRevision()).put("content_body_revision",previous.contentBodyRevision()).put("content_body_hash",previous.contentBodyHash()).put("input_hash",previous.inputHash()).put("result_hash",previous.resultHash()).put("generation",previous.generation());
            var time=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();value.put("cutoff",time.format(selected.cutoff())).put("input_cutoff",time.format(previous.inputCutoff())).put("applied_at",time.format(previous.appliedAt()));
            value.set("previous_proof",CanonicalJson.parse(selected.previous().proofJson()));value.set("current_proof",CanonicalJson.parse(selected.currentProofJson()));value.set("current_marker",CanonicalJson.parse(selected.current().json()));
            byte[] bytes=CanonicalJson.bytes(value);if(bytes.length>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();json=new String(bytes,StandardCharsets.UTF_8);hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,value);
        }
        /** Native immutable basis must still match; only monotonic entity CAS metadata may differ. */
        private Projection(Projection fresh,FrozenCatalogVersionProjections.Entry original){
            var old=CanonicalJson.parse(original.json());var current=fresh.toJson();
            for(String name:List.of("entity_revision","entity_generation")){
                if(WireValues.nonnegativeInteger(current.get(name))<WireValues.nonnegativeInteger(old.get(name)))throw new IllegalStateException("original catalog metadata regressed");
                ((tools.jackson.databind.node.ObjectNode)current).set(name,old.get(name));
                ((tools.jackson.databind.node.ObjectNode)current.get("current_proof")).set(name,old.get(name));
            }
            if(!current.equals(old))throw new IllegalStateException("original catalog immutable basis changed");
            json=original.json();hash=original.hash();judgmentKey=fresh.judgmentKey;category=fresh.category;entity=fresh.entity;content=fresh.content;root=fresh.root;member=fresh.member;changed=fresh.changed;claimTargetHash=fresh.claimTargetHash;
            oldProof=CanonicalJson.hash(CanonicalJson.Domain.INPUT,old.get("previous_proof"));newProof=CanonicalJson.hash(CanonicalJson.Domain.INPUT,old.get("current_proof"));
        }
        public String json(){return json;}public JsonNode toJson(){return CanonicalJson.parse(json);}public String hash(){return hash;}public String judgmentKey(){return judgmentKey;}
        public String category(){return category;}public String entityId(){return entity;}public String contentId(){return content;}public String rootId(){return root;}public String memberId(){return member;}public String claimTargetHash(){return claimTargetHash;}
        public boolean versionChanged(){return changed;}
        public FrozenCatalogVersionQualifications.Qualification resultConstraint(){return new FrozenCatalogVersionQualifications.Qualification(member,entity,changed,oldProof,newProof);}
        @Override public String toString(){return "CatalogVersionQualificationProjection[redacted]";}
    }
    private CatalogVersionQualificationProjection(){ }
    public static Projection read(Selection selected){return new Projection(Objects.requireNonNull(selected));}
    static Projection readOriginal(Selection selected,FrozenCatalogVersionProjections.Entry original){return new Projection(read(selected),Objects.requireNonNull(original));}
}
