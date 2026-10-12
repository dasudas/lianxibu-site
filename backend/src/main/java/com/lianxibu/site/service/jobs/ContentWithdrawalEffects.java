package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Joins the original review transaction; an outer permanent review receipt owns replay. */
public final class ContentWithdrawalEffects {
    private final VersionValidityLedgerService ledger;private final JdbcValidityPropagation queue;
    private final ContentReviewWriteService writer;private final Clock clock;
    public ContentWithdrawalEffects(VersionValidityLedgerService ledger,JdbcValidityPropagation queue,ContentReviewWriteService writer,Clock clock){
        this.ledger=Objects.requireNonNull(ledger);this.queue=Objects.requireNonNull(queue);this.writer=Objects.requireNonNull(writer);this.clock=Objects.requireNonNull(clock);
    }
    public void apply(ReviewContentState row,String cause,String reason,String actionKey){
        JdbcFrozenInputs.requireTx();
        if(row==null||row.bodyRevision()==null||row.bodyHashV1()==null||row.inputJobId()==null||"withdrawn".equals(row.state()))throw new ReviewPublicationService.Conflict("withdrawal_state_conflict");
        if(!Set.of("display_adjustment","conclusion_error").contains(cause)||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048||actionKey==null||actionKey.isBlank()||actionKey.length()>128)throw new IllegalArgumentException("invalid withdrawal");
        var version=new ContentVersionKey(row.kind(),row.id(),row.bodyRevision(),HexFormat.of().formatHex(row.bodyHashV1()),row.inputJobId());
        var target=FrozenReferenceClosure.version(version);ledger.lockStableIdentity(target);
        if(!ledger.exactVersionExists(target))throw new ReviewPublicationService.Conflict("invalid_evidence");
        String disposition=null;
        if("conclusion_error".equals(cause)){
            var validity=ledger.validity(target);
            if(validity.revision()>=CanonicalJson.MAX_SAFE_INTEGER)throw new ReviewPublicationService.Conflict("validity_revision_exhausted");
            var command=new VersionValidityService.Command(target,validity.revision(),VersionValidityService.Action.INVALIDATE,reason,null);
            String hash=VersionValidityService.commandHash(command,VersionValidityService.canonicalTarget(target));
            disposition=ledger.append(command,hash,validity.revision()+1,"withdraw-version:"+JdbcResultState.sha(actionKey));
            queue.enqueue(disposition,target,clock.instant().truncatedTo(ChronoUnit.MICROS));
        }
        writer.withdraw(row,disposition,"display_adjustment".equals(cause));
    }
}
