package com.lianxibu.site.service.learning;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.learning.JdbcLearningImports;
import com.lianxibu.site.repository.learning.JdbcLearningPackages;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.learning.LearningImportService.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class LearningImportJdbcStore implements LearningImportService.CommitStore {
    private final JdbcLearningImports store;private final JdbcLearningPackages packages;private final String actor;private final Clock clock;
    public LearningImportJdbcStore(JdbcLearningImports store,JdbcLearningPackages packages,String actor,Clock clock){this.store=Objects.requireNonNull(store);this.packages=Objects.requireNonNull(packages);this.actor=WireValues.id(actor,false);this.clock=Objects.requireNonNull(clock);}
    @Override public Result committed(String key,String hash){String value=store.receipt(actor,key,HexFormat.of().parseHex(hash));if(value==null)return null;var j=CanonicalJson.parse(value);return new Result(j.get("package_id").asText(),j.get("revision").longValue(),true);}
    @Override public Result apply(Plan plan,String requestedActor,Long expected,String hash,ActionContext action){
        if(!actor.equals(requestedActor)||plan==null||plan.items()==null||plan.items().size()>10000)throw new Rejected("invalid_import_scope");
        var declaredRoutes=new HashMap<String,Set<String>>();var declaredModules=new HashMap<String,Set<String>>();
        for(var item:plan.items())if(!item.has("from_id")){
            if(item.has("module_ids"))declaredRoutes.put(WireValues.id(item.get("id"),false),Set.copyOf(ids(item.get("module_ids"))));
            if(item.has("route_ids"))declaredModules.put(WireValues.id(item.get("id"),false),Set.copyOf(ids(item.get("route_ids"))));
        }
        for(var route:declaredRoutes.entrySet())for(var module:declaredModules.entrySet())if(route.getValue().contains(module.getKey())!=module.getValue().contains(route.getKey()))throw new Rejected("conflicting_import_relationships");
        String pkg=WireValues.id(plan.targetPackageId()==null?action.preallocatedResourceId():plan.targetPackageId(),false);
        long revision=store.begin(pkg,expected,plan.title(),clock.instant());int position=0;var unique=new HashSet<String>();
        // Objects first, so references can point forward within this transaction.
        for(var item:plan.items()){
            if(item.has("from_id"))continue;String kind=kind(item),id=WireValues.id(item.get("id"),false);
            if(!unique.add(kind+":"+id)||!plan.temporaryPackageId().equals(WireValues.text(item.get("package_id"))))throw new Rejected("invalid_import_identity");
            var body=JsonMapper.shared().createObjectNode();
            for(String field:List.of("scope","question","difficulty","description"))if(item.has(field))body.set(field,item.get(field));
            if(kind.equals("question"))body.set("question_kind",item.get("kind"));
            for(String field:List.of("goals","completion_criteria","assessment_points","requirements","tasks","deliverables","acceptance_criteria"))if(item.has(field))body.set(field+"_raw",item.get(field));
            String answer=item.has("answer")?WireValues.text(item.get("answer")):null;
            if(answer!=null)answer=answer.replace("\r\n","\n").replace('\r','\n');
            store.item(pkg,kind,id,WireValues.text(item.get("title")),canonical(body),item.has("revision")?WireValues.nonnegativeInteger(item.get("revision")):null,answer,item.has("answer"),position++);
        }
        var knownItems=new HashSet<>(store.itemIdentities(pkg));
        // Remove only relationship lists explicitly owned by supplied objects.
        for(var item:plan.items()){
            if(item.has("from_id"))continue;String kind=kind(item),id=item.get("id").asText();
            if(kind.equals("route"))store.clearOutgoing(pkg,"route",id,"contains","module");
            if(kind.equals("module"))store.clearIncoming(pkg,"module",id,"contains","route");
            if(kind.equals("node")){store.clearIncoming(pkg,"node",id,"contains","module");store.clearOutgoing(pkg,"node",id,"requires","node");store.clearOutgoing(pkg,"node",id,"references","resource");}
        }
        for(var item:plan.items()){
            if(item.has("from_id")){String relation=WireValues.text(item.get("kind"));if(!Set.of("prerequisite","related","contains").contains(relation))throw new Rejected("invalid_import_edge");checkedLink(knownItems,pkg,"node",WireValues.id(item.get("from_id"),false),relation.equals("prerequisite")?"requires":relation,"node",WireValues.id(item.get("to_id"),false),0);continue;}
            String kind=kind(item),id=item.get("id").asText();int order=0;
            if(kind.equals("route"))for(String module:ids(item.get("module_ids")))checkedLink(knownItems,pkg,"route",id,"contains","module",module,order++);
            if(kind.equals("module"))for(String route:ids(item.get("route_ids")))checkedLink(knownItems,pkg,"route",route,"contains","module",id,order++);
            if(kind.equals("node")){
                checkedLink(knownItems,pkg,"module",WireValues.id(item.get("module_id"),false),"contains","node",id,0);
                for(String dependency:ids(item.get("dependencies")))checkedLink(knownItems,pkg,"node",id,"requires","node",dependency,order++);
                for(var resource:item.get("resources")){
                    String resourceId=HexFormat.of().formatHex(digest(id+"\n"+canonical(resource)));var body=JsonMapper.shared().createObjectNode();body.set("url",resource.get("url"));
                    store.item(pkg,"resource",resourceId,WireValues.text(resource.get("title")),canonical(body),null,null,false,order);
                    knownItems.add("resource:"+resourceId);
                    checkedLink(knownItems,pkg,"node",id,"references","resource",resourceId,order++);
                }
            }
        }
        if(!LearningPackageValidation.valid(packages.items(pkg),packages.links(pkg)))throw new Rejected("package_validation_failed");
        store.finish(pkg,revision);var result=JsonMapper.shared().createObjectNode();result.put("package_id",pkg);result.put("revision",revision);result.put("title",plan.title());result.put("description",plan.description());
        store.receipt(actor,action.actionKey(),digest(action.actionKey()),HexFormat.of().parseHex(hash),pkg,revision,canonical(result),clock.instant());return new Result(pkg,revision,false);
    }
    public static String kind(JsonNode item){if(item.has("question"))return "question";if(item.has("scope"))return "node";if(item.has("requirements"))return "project";if(item.has("module_ids"))return "route";if(item.has("route_ids"))return "module";throw new Rejected("invalid_import_item");}
    private void checkedLink(Set<String> known,String pkg,String fromKind,String from,String relation,String toKind,String to,int position){
        if(!known.contains(fromKind+":"+from)||!known.contains(toKind+":"+to))throw new Rejected("package_validation_failed");
        store.link(pkg,fromKind,from,relation,toKind,to,position);
    }
    private static List<String> ids(JsonNode array){if(array==null||!array.isArray())throw new Rejected("invalid_import_references");var values=new ArrayList<String>();var seen=new HashSet<String>();for(var item:array){String id=WireValues.id(item,false);if(!seen.add(id))throw new Rejected("duplicate_import_reference");values.add(id);}return values;}
    private static String canonical(JsonNode value){return new String(CanonicalJson.bytes(value),StandardCharsets.UTF_8);}
    private static byte[] digest(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
