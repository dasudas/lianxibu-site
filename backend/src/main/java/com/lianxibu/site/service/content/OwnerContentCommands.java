package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.ContentReviewEvidenceService;
import com.lianxibu.site.service.jobs.ReviewPublicationJdbcStore.Command;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/** Owner review commands bind the authenticated actor to a permanent exact-command receipt. */
public final class OwnerContentCommands {
    private final BiFunction<String,Command,ReviewPublicationService> reviews;
    public OwnerContentCommands(BiFunction<String,Command,ReviewPublicationService> reviews){this.reviews=Objects.requireNonNull(reviews);}

    public ContentService.ActionResult action(Actor actor,String kind,String id,ContentService.ContentAction request,
            String key,String csrf,String origin) {
        String actorId=OwnerContentReadService.owner(actor);WireValues.id(id,false);
        if(request==null||request.action()==null||!Set.of("report","agent","model","skill","workflow").contains(kind)
            ||key==null||key.isBlank()||key.length()>128||csrf==null||csrf.isBlank()||origin==null||origin.isBlank())throw new IllegalArgumentException("invalid content command");
        if(!Set.of("approve","publish","reject","withdraw").contains(request.action()))throw unavailable();
        var command=new Command(request.action(),kind,id,request.expectedRevision(),null,request.reason(),request.withdrawalReason());
        String operation=ContentReviewEvidenceService.operationId(key);
        try {
            var service=Objects.requireNonNull(reviews.apply(actorId,command));
            var context=new ActionContext(key,operation,null);
            var receipt=switch(request.action()){
                case "approve"->service.approve(kind,id,request.expectedRevision(),context);
                case "publish"->service.publish(kind,id,request.expectedRevision(),context);
                case "reject"->service.reject(kind,id,request.expectedRevision(),context);
                case "withdraw"->service.withdraw(kind,id,request.expectedRevision(),request.withdrawalReason(),context);
                default->throw unavailable();
            };
            if(receipt==null||!receipt.kind().equals(kind)||!receipt.id().equals(id))throw unavailable();
            return new ContentService.ActionResult(operation,new ContentService.ResourceRef(kind,id,null),receipt.revision(),null,null,null,List.of(),null);
        } catch(IdentityService.Rejected refusal){throw refusal;}
        catch(ReviewPublicationService.Conflict conflict){throw new IdentityService.Rejected(IdentityService.Refusal.REVISION_CONFLICT,null);}
        catch(RuntimeException failure){throw unavailable();}
    }
    private static IdentityService.Rejected unavailable(){return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
}
