package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One authenticated immutable question. Audit reason is a separate value. */
public final class OwnerAdHocCreateCommand {
    private final String actor,target,prompt,reason,json,hash,logical;
    private OwnerAdHocCreateCommand(String actor,String target,String prompt,String reason){
        WireValues.id(actor,false);WireValues.id(target,false);text(prompt,32768);text(reason,2048);
        this.actor=actor;this.target=target;this.prompt=prompt;this.reason=reason;
        var body=JsonMapper.shared().createObjectNode().put("task_type","ad_hoc_ai").put("actor",actor).put("expected_revision",0).put("reason",reason);
        body.putObject("target").put("kind","ad_hoc_request").put("id",target).putNull("package_id");body.putObject("request").put("prompt",prompt);
        json=JdbcFrozenInputs.canonical(body);hash=CanonicalJson.hash(CanonicalJson.Domain.ACTION,body);
        var natural=JsonMapper.shared().createObjectNode().put("stage","ad_hoc_ai").put("prompt",prompt);
        logical="ad-hoc:"+CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,natural);
    }
    public static OwnerAdHocCreateCommand create(Actor actor,String target,String prompt,String reason){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);
        if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        return new OwnerAdHocCreateCommand(actor.userId(),target,prompt,reason);
    }
    /** Native receipt decoding, never an authentication or write entry. */
    public static OwnerAdHocCreateCommand original(JsonNode body){
        var fields=Set.of("task_type","actor","expected_revision","reason","target","request");WireValues.object(body,fields,fields);
        var target=body.get("target");var refs=Set.of("kind","id","package_id");WireValues.object(target,refs,refs);
        WireValues.object(body.get("request"),Set.of("prompt"),Set.of("prompt"));
        if(!"ad_hoc_ai".equals(WireValues.text(body.get("task_type")))||WireValues.nonnegativeInteger(body.get("expected_revision"))!=0||!"ad_hoc_request".equals(WireValues.text(target.get("kind")))||!target.get("package_id").isNull())throw new IllegalArgumentException("invalid original temporary command");
        var result=new OwnerAdHocCreateCommand(WireValues.id(body.get("actor"),false),WireValues.id(target.get("id"),false),WireValues.text(body.get("request").get("prompt")),WireValues.text(body.get("reason")));
        if(!result.json.equals(JdbcFrozenInputs.canonical(body)))throw new IllegalArgumentException("invalid original temporary command");return result;
    }
    private static void text(String value,int maximum){if(value==null||value.isBlank()||value.codePointCount(0,value.length())>maximum)throw new IllegalArgumentException("invalid temporary command");CanonicalJson.bytes(JsonMapper.shared().valueToTree(value));}
    public String actor(){return actor;} public String target(){return target;} public String prompt(){return prompt;} public String reason(){return reason;}
    public String json(){return json;} public String hash(){return hash;} public String logicalKey(){return logical;}
    public String intentId(){return JdbcFrozenInputs.stableId("intent",logical+":1");}
    public String rootId(){return JdbcFrozenInputs.stableId("root",logical+":1:0");}
    @Override public String toString(){return "OwnerAdHocCreateCommand[redacted]";}
}
