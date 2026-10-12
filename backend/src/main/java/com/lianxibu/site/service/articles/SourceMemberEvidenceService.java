package com.lianxibu.site.service.articles;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Verifies retained source facts before they enter a new frozen input. */
public final class SourceMemberEvidenceService {
    public record Fact(String id,long revision,String category,String identityKey,String title,String summary,String payloadJson) { }
    private final JdbcSourceMemberEvidence store;
    public SourceMemberEvidenceService(JdbcSourceMemberEvidence store){this.store=Objects.requireNonNull(store);}
    public List<Fact> read(Set<String> ids,Instant cutoff) {
        return read(ids,cutoff,false);
    }
    /** Current committed facts for preflight only. A later writer must validate again. */
    public List<Fact> readObserved(Set<String> ids,Instant cutoff){return read(ids,cutoff,true);}
    private List<Fact> read(Set<String> ids,Instant cutoff,boolean observed){
        if(ids==null||ids.isEmpty()||ids.size()>100||cutoff==null)throw invalid();
        List<Fact> facts=new ArrayList<>();
        var ordered=ids.stream().sorted().toList();
        for(int offset=0;offset<ordered.size();offset+=16){
            var batch=ordered.subList(offset,Math.min(offset+16,ordered.size()));var exact=Set.copyOf(batch);var rows=observed?store.observedBatch(exact):store.lockBatch(exact);
            for(String id:batch) {
            WireValues.id(id,false);var row=rows.get(id);
            if(row==null||!id.equals(row.id())||!Objects.equals(row.runId(),row.runIdentity())||!Objects.equals(row.sourceId(),row.runSource())
                ||row.revision()<0||row.revision()>=CanonicalJson.MAX_SAFE_INTEGER||!"production".equals(row.activation())
                ||!Set.of("complete","partial").contains(row.delivery())||!"accepted".equals(row.terminal())||!row.valid()
                ||!Set.of("pending","ready").contains(row.state())||"needs_review".equals(row.projection())
                ||row.createdAt()==null||row.createdAt().isAfter(cutoff)||row.finishedAt()==null||row.finishedAt().isAfter(cutoff)
                ||row.payload()==null||row.payload().getBytes(StandardCharsets.UTF_8).length>200*1024||row.hash()==null)throw invalid();
            var payload=CanonicalJson.parse(row.payload());byte[] canonical=CanonicalJson.bytes(payload);
            if(!MessageDigest.isEqual(sha(canonical),row.hash()))throw invalid();
            String kind="news_tech".equals(row.category())?"article":payload.has("subject")?"metric":"catalog";
            ObservationPayloadPolicy.validateStored(kind,payload,row.sourceId());
            var config=CanonicalJson.parse(row.config());
            if(WireValues.nonnegativeInteger(payload.get("config_version"))!=row.configVersion()
                ||!WireValues.text(payload.get("parser_version")).equals(WireValues.text(config.get("parser_version"))))throw invalid();
            String title,summary;tools.jackson.databind.JsonNode identity;
            if(kind.equals("article")) {
                var value=JsonMapper.shared().createObjectNode();value.put("namespace","article_url");value.set("key",payload.get("source_url"));identity=value;
                title=WireValues.text(payload.get("title"));summary=payload.get("source_summary").isNull()?null:WireValues.text(payload.get("source_summary"));
            } else {
                var owner=kind.equals("metric")?payload.get("subject"):payload;
                if(!row.category().equals(WireValues.text(owner.get("category"))))throw invalid();identity=owner.get("identity");
                title=kind.equals("metric")?WireValues.text(identity.get("key")):WireValues.text(payload.get("title"));
                summary=kind.equals("metric")||payload.get("summary").isNull()?null:WireValues.text(payload.get("summary"));
            }
            String identityKey=new String(CanonicalJson.bytes(identity),StandardCharsets.UTF_8);
            if(identityKey.codePointCount(0,identityKey.length())>4096||title.isBlank()||title.codePointCount(0,title.length())>512)throw invalid();
            facts.add(new Fact(id,row.revision(),row.category(),identityKey,title,summary,new String(canonical,StandardCharsets.UTF_8)));
        }
        }
        return List.copyOf(facts);
    }
    private static byte[] sha(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static IllegalStateException invalid(){return new IllegalStateException("source member evidence unavailable");}
}
