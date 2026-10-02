package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Complete transitive references from immutable version proofs. Current relations
 * cannot serve as the Source of an old version. Validity is evaluated separately. */
public final class FrozenReferenceClosure {
    public record Evidence(ContentVersionKey provenVersion,FrozenApplicationContext context) { }
    public interface Source {
        Evidence resolve(ContentVersionKey exactVersion);
        boolean leafExists(JsonNode exactLeaf);
    }
    public static final class Closed {
        private final List<String> targets;
        private Closed(List<JsonNode> nodes){targets=nodes.stream().map(FrozenReferenceClosure::canonical).toList();}
        public List<JsonNode> targets(){return targets.stream().map(CanonicalJson::parse).toList();}
    }
    private record Frame(String key,Iterator<JsonNode> targets) { }
    private final Source source;
    private final int limit;
    public FrozenReferenceClosure(Source source){this(source,10000);}
    FrozenReferenceClosure(Source source,int limit){this.source=Objects.requireNonNull(source);if(limit<1||limit>10000)throw new IllegalArgumentException("invalid closure limit");this.limit=limit;}
    public Closed expand(ContentVersionKey owner,FrozenApplicationContext context){
        verifyOwner(owner,context);String ownerKey=key(version(owner));var active=new HashSet<String>();active.add(ownerKey);var complete=new HashSet<String>();var all=new HashMap<String,JsonNode>();var stack=new ArrayDeque<Frame>();stack.push(new Frame(ownerKey,context.referenceTargets().iterator()));
        while(!stack.isEmpty()){
            var frame=stack.peek();if(!frame.targets().hasNext()){stack.pop();active.remove(frame.key());complete.add(frame.key());continue;}
            var target=frame.targets().next();String canonical=key(target);if(active.contains(canonical))throw unavailable();if(all.putIfAbsent(canonical,target)==null&&all.size()>limit)throw unavailable();if(complete.contains(canonical))continue;
            if("content_version".equals(target.path("kind").asText())){
                var requested=ContentVersionKey.fromJson(target);if(requested.revision()<1||requested.inputJobId()==null)throw unavailable();var evidence=source.resolve(requested);if(evidence==null||!requested.equals(evidence.provenVersion()))throw unavailable();verifyOwner(evidence.provenVersion(),evidence.context());active.add(canonical);stack.push(new Frame(canonical,evidence.context().referenceTargets().iterator()));
            }else{
                if(!Set.of("article_revision","catalog_evidence").contains(target.path("kind").asText())||!source.leafExists(CanonicalJson.parse(CanonicalJson.bytes(target))))throw unavailable();complete.add(canonical);
            }
        }
        var sorted=new ArrayList<>(all.values());sorted.sort((a,b)->Arrays.compareUnsigned(CanonicalJson.bytes(a),CanonicalJson.bytes(b)));return new Closed(sorted);
    }
    private static void verifyOwner(ContentVersionKey owner,FrozenApplicationContext context){
        if(owner==null||owner.revision()<1||owner.inputJobId()==null||context==null||!context.referencesComplete()||!owner.contentKind().equals(context.contentKind())||!owner.contentId().equals(context.contentId()))throw unavailable();ContentVersionKey.fromJson(version(owner));
    }
    static JsonNode version(ContentVersionKey key){var node=JsonMapper.shared().createObjectNode();node.put("kind","content_version");node.put("content_kind",key.contentKind());node.put("content_id",key.contentId());node.put("revision",key.revision());node.put("body_hash",key.bodyHash());node.put("input_job_id",key.inputJobId());return node;}
    private static String canonical(JsonNode node){return new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);}
    private static String key(JsonNode target){return canonical(target);}
    private static IllegalStateException unavailable(){return new IllegalStateException("complete immutable reference closure unavailable");}
}
