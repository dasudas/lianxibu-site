package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.ContentVersionKey;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.jobs.VersionValidityService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Owner commands for one explicit immutable version or source evidence identity. */
public final class OwnerContentValidityService {
    public static final class Refused extends RuntimeException {
        private final int status;private final String code;
        private Refused(int status,String code){super(code);this.status=status;this.code=code;}
        public int status(){return status;}public String code(){return code;}
    }
    private final Function<String,VersionValidityService> services;
    public OwnerContentValidityService(Function<String,VersionValidityService> services){this.services=Objects.requireNonNull(services);}
    public ContentService.ActionResult version(Actor actor,String kind,String id,ContentService.VersionAction request,String key,String csrf,String origin){
        String actorId=owner(actor,key,csrf,origin);WireValues.id(id,false);
        if(request==null||request.target()==null||!Set.of("report","agent","model","skill","workflow").contains(kind))throw invalid("validation");
        var target=request.target();
        if(!"content_version".equals(target.kind())||!Objects.equals(kind,target.contentKind())||!Objects.equals(id,target.contentId()))throw invalid("target_mismatch");
        var version=JsonMapper.shared().createObjectNode();version.put("kind","content_version");version.put("content_kind",kind);version.put("content_id",id);
        version.put("revision",target.revision());version.put("body_hash",target.bodyHash());version.put("input_job_id",target.inputJobId());
        try{ContentVersionKey.fromJson(version);if(target.revision()<1)throw invalid("validation");}
        catch(IllegalArgumentException failure){throw invalid("validation");}
        var action=switch(request.action()){case "invalidate_conclusion"->VersionValidityService.Action.INVALIDATE;case "reverse_invalidation"->VersionValidityService.Action.REVERSE_INVALIDATION;default->throw invalid("validation");};
        return change(actorId,kind,id,version,request.expectedValidityRevision(),action,request.reason(),request.invalidationId(),key);
    }
    public ContentService.ActionResult evidence(Actor actor,String kind,String id,ContentService.EvidenceAction request,String key,String csrf,String origin){
        String actorId=owner(actor,key,csrf,origin);WireValues.id(id,false);
        if(request==null||!Set.of("article_revision","catalog_evidence").contains(kind))throw invalid("validation");
        var target=JsonMapper.shared().createObjectNode();target.put("kind",kind);target.put(kind.equals("article_revision")?"id":"item_id",id);
        var action=switch(request.action()){case "invalidate"->VersionValidityService.Action.INVALIDATE;case "reverse_invalidation"->VersionValidityService.Action.REVERSE_INVALIDATION;default->throw invalid("validation");};
        return change(actorId,kind,id,target,request.expectedValidityRevision(),action,request.reason(),request.invalidationId(),key);
    }
    private ContentService.ActionResult change(String actor,String kind,String id,JsonNode target,long revision,VersionValidityService.Action action,String reason,String reverse,String key){
        if(revision<0||revision>=CanonicalJson.MAX_SAFE_INTEGER||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048
            ||(action==VersionValidityService.Action.INVALIDATE?reverse!=null:reverse==null||reverse.isBlank()))throw invalid("validation");
        if(reverse!=null)WireValues.id(reverse,false);
        try {
            var result=services.apply(actor).change(new VersionValidityService.Command(target,revision,action,reason,reverse),new ActionContext(key,id,null));
            var resource=new ContentService.ResourceRef(kind,id,null);
            return new ContentService.ActionResult(result.dispositionId(),resource,result.validityRevision(),null,null,null,List.of(resource),null);
        }catch(VersionValidityService.Rejected failure){
            throw switch(failure.getMessage()){
                case "unknown_version"->invalid("unknown_version");
                case "validity_revision_conflict","invalidation_not_active","command_identity_conflict"->new Refused(409,failure.getMessage());
                default->unavailable();
            };
        }catch(IdentityService.Rejected failure){throw failure;}catch(RuntimeException failure){throw unavailable();}
    }
    private static String owner(Actor actor,String key,String csrf,String origin){String id=OwnerContentReadService.owner(actor);if(key==null||!key.matches("[!-~]{1,128}")||csrf==null||csrf.isBlank()||origin==null||origin.isBlank())throw invalid("validation");return id;}
    private static Refused invalid(String code){return new Refused(422,code);}
    private static IdentityService.Rejected unavailable(){return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
}
