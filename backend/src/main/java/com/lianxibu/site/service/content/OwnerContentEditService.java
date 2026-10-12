package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.ContentReviewEvidenceService;
import com.lianxibu.site.service.jobs.ReviewPublicationJdbcStore.Command;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Editing returns the permanently recorded result version, never a later current body. */
public final class OwnerContentEditService {
    public record Operation(ReviewPublicationService reviews,Supplier<ContentReviewEvidenceService.Recorded> recorded) { }
    @FunctionalInterface public interface Factory {Operation create(String actor,Command command,String key);}
    private final Factory factory;private final FrozenPublishedProjection projection;private final TransactionTemplate reads;
    public OwnerContentEditService(Factory factory,FrozenPublishedProjection projection,PlatformTransactionManager tx){
        this.factory=Objects.requireNonNull(factory);this.projection=Objects.requireNonNull(projection);
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public ContentService.ContentView write(Actor actor,String kind,String id,ContentService.ContentWrite request,String key,String csrf,String origin){
        String actorId=OwnerContentReadService.owner(actor);WireValues.id(id,false);
        if(request==null||request.body()==null||!Set.of("report","agent","model","skill","workflow").contains(kind)
            ||key==null||!key.matches("[!-~]{1,128}")||csrf==null||csrf.isBlank()||origin==null||origin.isBlank())throw new IllegalArgumentException("invalid edit command");
        var body=PublishedContentFacade.body(request.body());FrozenContentBody.read(kind,body);
        String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body),json=new String(CanonicalJson.bytes(body),StandardCharsets.UTF_8);
        var command=new Command("edit",kind,id,request.expectedRevision(),hash,"Owner edited content");
        try {
            var operation=Objects.requireNonNull(factory.create(actorId,command,key));
            var receipt=operation.reviews().edit(kind,id,request.expectedRevision(),json,new ActionContext(key,ContentReviewEvidenceService.operationId(key),null));
            return reads.execute(status->{
                var recorded=operation.recorded().get();
                if(recorded==null||!receipt.equals(recorded.receipt())||!"draft".equals(receipt.state())
                    ||!kind.equals(recorded.version().contentKind())||!id.equals(recorded.version().contentId())||!hash.equals(recorded.version().bodyHash()))throw unavailable();
                var version=recorded.version();var proof=recorded.proof();
                return projection.projectOwner(new PublishedContentFacade.Version(kind,id,WireValues.text(CanonicalJson.parse(proof.bodyJson()).get("title")),
                    receipt.revision(),version.revision(),null,version.inputJobId(),version.bodyHash(),proof.bodyJson()),"draft",false);
            });
        }catch(IdentityService.Rejected refusal){throw refusal;}
        catch(ReviewPublicationService.Conflict conflict){throw new IdentityService.Rejected(IdentityService.Refusal.REVISION_CONFLICT,null);}
        catch(RuntimeException failure){throw unavailable();}
    }
    private static IdentityService.Rejected unavailable(){return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
}
