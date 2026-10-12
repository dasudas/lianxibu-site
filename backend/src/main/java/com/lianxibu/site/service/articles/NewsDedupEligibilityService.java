package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.articles.JdbcNewsDedupEligibility;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Complete P1 decisions; excluded material is retained and keywords never suppress counting. */
public final class NewsDedupEligibilityService {
    public enum Reason { ELIGIBLE,COLLECTOR_EXCLUDED,NOT_AI_ELIGIBLE,ALREADY_APPLIED }
    public record Entry(String itemId,Reason reason,SourceMemberEvidenceService.Fact fact,LocalDate businessDate,Instant completedAt) {
        public Entry {
            WireValues.id(itemId,false);Objects.requireNonNull(reason);
            if(reason==Reason.ELIGIBLE?fact==null||businessDate==null||completedAt==null||completedAt.getNano()%1000!=0||!itemId.equals(fact.id())
                    :fact!=null||businessDate!=null||completedAt!=null)throw unavailable();
        }
        @Override public String toString(){return "NewsQualification[redacted]";}
    }
    public record Selection(String runId,String sourceId,LocalDate day,Instant cutoff,List<Entry> entries) {
        public Selection {entries=List.copyOf(entries);}
        @Override public String toString(){return "NewsDedupSelection[redacted]";}
    }
    public static final class CapacityGuarded extends RuntimeException {public CapacityGuarded(){super("capacity_guard");}}
    private final JdbcNewsDedupEligibility metadata;private final SourceMemberEvidenceService evidence;
    public NewsDedupEligibilityService(JdbcNewsDedupEligibility metadata,SourceMemberEvidenceService evidence){this.metadata=Objects.requireNonNull(metadata);this.evidence=Objects.requireNonNull(evidence);}
    public Selection read(MemberUniverse.Universe universe,LocalDate day,Instant cutoff) {
        try {
            if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw unavailable();
            if(universe==null||day==null||cutoff==null)throw unavailable();
            Instant start=day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),end=day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            if(cutoff.isBefore(end))throw unavailable();var entries=new TreeMap<String,Entry>();long bytes=0;
            for(var member:universe.members()) {
                if("excluded".equals(member.terminalState())){entries.put(member.itemId(),new Entry(member.itemId(),Reason.COLLECTOR_EXCLUDED,null,null,null));continue;}
                var row=metadata.lock(member.itemId());
                if(row==null||!member.itemId().equals(row.id())||!universe.runId().equals(row.runId())||!universe.runId().equals(row.actualRun())
                        ||!universe.sourceId().equals(row.sourceId())||!universe.sourceId().equals(row.runSource())||!Objects.equals(member.sourceItemId(),row.sourceItemId())
                        ||!day.equals(row.day())||!"news_tech".equals(row.category())||!"accepted".equals(row.terminal())||row.payloadHash()==null
                        ||!Arrays.equals(HexFormat.of().parseHex(member.payloadHash()),row.payloadHash())||row.revision()<0||row.revision()>=CanonicalJson.MAX_SAFE_INTEGER
                        ||!"production".equals(row.activation())||!Set.of("complete","partial").contains(row.delivery())
                        ||!Set.of("pending","ready","needs_review","excluded").contains(row.state())
                        ||!Set.of("initialized","unchanged","promoted","historical","needs_review").contains(row.projection())
                        ||row.created()==null||row.finished()==null||row.created().getNano()%1000!=0||row.finished().getNano()%1000!=0)throw unavailable();
                Reason reason=Reason.ELIGIBLE;SourceMemberEvidenceService.Fact fact=null;
                if(!row.valid()||Set.of("needs_review","excluded").contains(row.state())||"needs_review".equals(row.projection())
                        ||row.created().isAfter(cutoff)||row.finished().isAfter(cutoff)||row.targetDate()!=null&&!day.equals(row.targetDate()))reason=Reason.NOT_AI_ELIGIBLE;
                else if(row.entityId()!=null){WireValues.id(row.entityId(),false);if(!"ready".equals(row.state()))throw unavailable();reason=Reason.ALREADY_APPLIED;}
                else {
                    var facts=evidence.read(Set.of(member.itemId()),cutoff);if(facts.size()!=1)throw unavailable();fact=facts.getFirst();
                    if(fact.revision()!=row.revision()||!row.category().equals(fact.category()))throw unavailable();
                    var payload=CanonicalJson.parse(fact.payloadJson());String basis=WireValues.text(payload.get("time_basis"));
                    var rawTime=switch(basis){case "published"->payload.get("published_at");case "updated"->payload.get("updated_at");case "submitted"->payload.get("submitted_at");default->null;};
                    Instant sourceTime=rawTime==null||rawTime.isNull()?null:WireValues.utcMicroseconds(rawTime);
                    if(sourceTime==null||sourceTime.isBefore(start)||!sourceTime.isBefore(end)){reason=Reason.NOT_AI_ELIGIBLE;fact=null;}
                    else {
                        bytes+=fact.payloadJson().getBytes(StandardCharsets.UTF_8).length+fact.identityKey().getBytes(StandardCharsets.UTF_8).length
                                +fact.title().getBytes(StandardCharsets.UTF_8).length+(fact.summary()==null?0:fact.summary().getBytes(StandardCharsets.UTF_8).length);
                        if(bytes>32L*1024*1024)throw new CapacityGuarded();
                    }
                }
                Instant available=reason==Reason.ELIGIBLE?(row.created().isAfter(row.finished())?row.created():row.finished()):null;
                entries.put(member.itemId(),new Entry(member.itemId(),reason,fact,reason==Reason.ELIGIBLE?row.day():null,available));
            }
            if(entries.size()!=universe.members().size())throw unavailable();return new Selection(universe.runId(),universe.sourceId(),day,cutoff,new ArrayList<>(entries.values()));
        }catch(CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw unavailable();}
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("complete news qualification unavailable");}
}
