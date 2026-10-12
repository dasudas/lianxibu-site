package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure P2 material view. The original fact provider owns qualification and retained evidence. */
public record NewsValueMaterial(String itemId,long expectedRevision,String originalBodyHash,String inputBasis,String text) {
    public static final String VERSION="news-value-material-v1";
    private static final Set<String> FIELDS=Set.of("schema_version","item_id","expected_revision","original_body_hash","input_basis","text");
    public NewsValueMaterial {
        WireValues.id(itemId,false);
        if(expectedRevision<0||expectedRevision>=CanonicalJson.MAX_SAFE_INTEGER||originalBodyHash==null||!originalBodyHash.matches("[0-9a-f]{64}")
                ||inputBasis==null||!Set.of("summary","body_prefix","missing").contains(inputBasis))throw invalid();
        if("missing".equals(inputBasis)){if(text!=null)throw invalid();}
        else if(text==null||text.isBlank()||!text.equals(text.stripLeading())||"summary".equals(inputBasis)&&!text.equals(text.strip())||text.codePointCount(0,text.length())>("summary".equals(inputBasis)?16384:800))throw invalid();
        if(text!=null)CanonicalJson.bytes(JsonMapper.shared().getNodeFactory().stringNode(text));
    }
    public static NewsValueMaterial from(String sourceId,SourceMemberEvidenceService.Fact fact) {
        try {
            WireValues.id(sourceId,true);Objects.requireNonNull(fact);
            if(!"news_tech".equals(fact.category())||fact.payloadJson()==null||fact.payloadJson().getBytes(StandardCharsets.UTF_8).length>200*1024)throw invalid();
            var body=CanonicalJson.parse(fact.payloadJson());ObservationPayloadPolicy.validateStored("article",body,sourceId);
            String canonical=new String(CanonicalJson.bytes(body),StandardCharsets.UTF_8);
            var identity=JsonMapper.shared().createObjectNode();identity.put("namespace","article_url");identity.set("key",body.get("source_url"));
            String summary=nullable(body.get("source_summary"));
            if(!canonical.equals(fact.payloadJson())||!fact.identityKey().equals(new String(CanonicalJson.bytes(identity),StandardCharsets.UTF_8))
                    ||!fact.title().equals(WireValues.text(body.get("title")))||!Objects.equals(fact.summary(),summary))throw invalid();
            String value=summary==null?null:summary.strip(),basis;
            if(value!=null&&!value.isBlank())basis="summary";
            else {
                String full=nullable(body.get("body_text"));full=full==null?null:full.strip();
                if(full==null||full.isBlank()){basis="missing";value=null;}
                else {basis="body_prefix";value=full.substring(0,full.offsetByCodePoints(0,Math.min(800,full.codePointCount(0,full.length()))));}
            }
            return new NewsValueMaterial(fact.id(),fact.revision(),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body),basis,value);
        }catch(RuntimeException rejected){throw invalid();}
    }
    public String json(){return new String(CanonicalJson.bytes(toJson()),StandardCharsets.UTF_8);}
    public JsonNode toJson(){var node=JsonMapper.shared().createObjectNode();node.put("schema_version",VERSION);node.put("item_id",itemId);node.put("expected_revision",expectedRevision);node.put("original_body_hash",originalBodyHash);node.put("input_basis",inputBasis);if(text==null)node.putNull("text");else node.put("text",text);return node;}
    public static NewsValueMaterial read(JsonNode node) {
        try {
            WireValues.object(node,FIELDS,FIELDS);
            if(CanonicalJson.bytes(node).length>128*1024||!VERSION.equals(WireValues.text(node.get("schema_version"))))throw invalid();
            return new NewsValueMaterial(WireValues.id(node.get("item_id"),false),WireValues.nonnegativeInteger(node.get("expected_revision")),WireValues.text(node.get("original_body_hash")),WireValues.text(node.get("input_basis")),nullable(node.get("text")));
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static String nullable(JsonNode node){return node.isNull()?null:WireValues.text(node);}
    @Override public String toString(){return "NewsValueMaterial[redacted]";}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("original news value material unavailable");}
}
