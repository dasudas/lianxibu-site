package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.Input;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact pre-application member versions and trusted identities, covered by input-v1. */
public final class FrozenMemberBindings {
    public static final String FIELD="member_bindings_v1";
    public record Member(String itemId,long expectedRevision,String category,String identityKey,String title,String summary,String newEntityId) { }
    private static final Set<String> CATEGORIES=Set.of("news_tech","agent","skill","workflow","model");
    private FrozenMemberBindings() { }
    public static Map<String,Member> read(JsonNode snapshot,Input input) {
        if(input==null||snapshot==null||!input.inputHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot)))throw invalid();
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        if(!context.referencesComplete()||!input.equals(context.input(input.rootId(),input.attemptId(),input.inputHash(),input.expectedRootRevision()))
            ||!(input.stage().dedup()||input.stage()==GenerationFlowService.Stage.NEWS_VALUE))throw invalid();
        var profile=snapshot.get(FIELD);closed(profile,"schema_version","members");
        if(!"frozen-member-bindings-v1".equals(WireValues.text(profile.get("schema_version"))))throw invalid();
        var members=profile.get("members");if(!members.isArray()||members.isEmpty()||members.size()>100)throw invalid();
        Map<String,Member> result=new LinkedHashMap<>();
        for(var member:members) {
            closed(member,"item_id","expected_revision","category","identity_key","title","summary","new_entity_id");
            String id=WireValues.id(member.get("item_id"),false),category=WireValues.text(member.get("category"));
            long revision=WireValues.nonnegativeInteger(member.get("expected_revision"));
            String identity=text(member.get("identity_key"),1,4096),title=text(member.get("title"),1,512),summary=member.get("summary").isNull()?null:text(member.get("summary"),0,16384);
            String allocated=WireValues.id(member.get("new_entity_id"),false);
            if(revision>=CanonicalJson.MAX_SAFE_INTEGER||!CATEGORIES.contains(category)
                ||input.stage().dedup()&&!category.equals(category(input.stage()))
                ||!allocated.equals(entityId(input.rootId(),id,category,identity)))throw invalid();
            if(result.putIfAbsent(id,new Member(id,revision,category,identity,title,summary,allocated))!=null)throw invalid();
        }
        if(!result.keySet().equals(input.itemIds()))throw invalid();return Collections.unmodifiableMap(result);
    }
    public static String entityId(String rootId,String itemId,String category,String identity) {
        WireValues.id(rootId,false);WireValues.id(itemId,false);
        if(!CATEGORIES.contains(category)||identity==null||identity.isBlank()||identity.codePointCount(0,identity.length())>4096)throw invalid();
        var value=JsonMapper.shared().createObjectNode();value.put("scope","frozen-entity-v1");value.put("root_id",rootId);value.put("item_id",itemId);value.put("category",category);value.put("identity_key",identity);
        return CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,value);
    }
    private static String category(GenerationFlowService.Stage stage) {return stage==GenerationFlowService.Stage.NEWS_DEDUP?"news_tech":stage.name().toLowerCase(Locale.ROOT).replace("_dedup","");}
    private static String text(JsonNode node,int min,int max) {String value=WireValues.text(node);int count=value.codePointCount(0,value.length());if(count<min||count>max)throw invalid();return value;}
    private static void closed(JsonNode node,String... fields) {var names=Set.of(fields);WireValues.object(node,names,names);}
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("invalid_frozen_member_bindings");}
}
