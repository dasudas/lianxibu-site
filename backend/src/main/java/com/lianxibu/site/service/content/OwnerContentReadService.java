package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner details retain original proof even when the version no longer qualifies publicly. */
public final class OwnerContentReadService {
    private final ContentReadService contents;
    private final FrozenPublishedProjection projection;
    private final ReviewPublicationService.Store review;
    private final TransactionTemplate reads;

    public OwnerContentReadService(ContentReadService contents,FrozenPublishedProjection projection,
            ReviewPublicationService.Store review,PlatformTransactionManager tx) {
        this.contents=Objects.requireNonNull(contents);this.projection=Objects.requireNonNull(projection);
        this.review=Objects.requireNonNull(review);reads=new TransactionTemplate(Objects.requireNonNull(tx));
        reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public ContentService.ContentView read(Actor actor,String kind,String id) {
        owner(actor);WireValues.id(id,false);
        if(!Set.of("report","agent","model","skill","workflow").contains(kind))throw new IllegalArgumentException("invalid content kind");
        try{return reads.execute(status->{
            var row=contents.reviewSnapshot(kind,id).orElseThrow(()->new IdentityService.Rejected(IdentityService.Refusal.NOT_FOUND,null));
            if(row.bodyRevision()==null||row.bodyHashV1()==null||row.inputJobId()==null)throw unavailable();
            var current=review.snapshot(row);
            boolean matches=ReviewPublicationService.matchesQualification(row,current);
            return projection.projectOwner(new PublishedContentFacade.Version(row.kind(),row.id(),row.title(),row.revision(),
                row.bodyRevision(),row.publishedAt(),row.inputJobId(),HexFormat.of().formatHex(row.bodyHashV1()),row.bodyJson()),row.state(),matches);
        });}catch(IdentityService.Rejected refusal){throw refusal;}catch(RuntimeException failure){throw unavailable();}
    }
    static String owner(Actor actor) {
        if(actor==null||actor.userId()==null||actor.userId().isBlank()||!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        return WireValues.id(actor.userId(),false);
    }
    private static IdentityService.Rejected unavailable(){return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
}
