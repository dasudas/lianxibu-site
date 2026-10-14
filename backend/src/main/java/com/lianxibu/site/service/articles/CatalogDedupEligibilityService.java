package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.articles.JdbcNewsDedupEligibility;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Complete P3-P6 decisions from original retained catalog/metric evidence. */
public final class CatalogDedupEligibilityService {
    public enum Reason { ELIGIBLE,COLLECTOR_EXCLUDED,NOT_AI_ELIGIBLE,ALREADY_APPLIED }
    public record Entry(String itemId,Reason reason,SourceMemberEvidenceService.Fact fact,LocalDate businessDate,Instant completedAt) {
        public Entry {
            WireValues.id(itemId,false);Objects.requireNonNull(reason);
            if(reason==Reason.ELIGIBLE?fact==null||businessDate==null||completedAt==null||completedAt.getNano()%1000!=0||!itemId.equals(fact.id())
                    :fact!=null||businessDate!=null||completedAt!=null)throw unavailable();
        }
        @Override public String toString(){return "CatalogQualification[redacted]";}
    }
    public record Selection(String runId,String sourceId,String category,LocalDate day,Instant cutoff,List<Entry> entries) {
        public Selection {entries=List.copyOf(entries);}
        @Override public String toString(){return "CatalogDedupSelection[redacted]";}
    }
    public static final class CapacityGuarded extends RuntimeException {public CapacityGuarded(){super("capacity_guard");}}
    private final JdbcNewsDedupEligibility metadata;private final SourceMemberEvidenceService evidence;
    public CatalogDedupEligibilityService(JdbcNewsDedupEligibility metadata,SourceMemberEvidenceService evidence){this.metadata=Objects.requireNonNull(metadata);this.evidence=Objects.requireNonNull(evidence);}
    public Selection read(MemberUniverse.Universe universe,String category,LocalDate day,Instant cutoff) {
        try {
            if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw unavailable();
            if(universe==null||category==null||!Set.of("agent","skill","workflow","model").contains(category)||day==null||cutoff==null||cutoff.getNano()%1000!=0)throw unavailable();
            Instant end=day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            if(cutoff.isBefore(end))throw unavailable();var entries=new TreeMap<String,Entry>();long bytes=0;
            var accepted=universe.members().stream().filter(member->!"excluded".equals(member.terminalState())).toList();
            var captured=new HashMap<String,JdbcNewsDedupEligibility.Row>();
            for(int offset=0;offset<accepted.size();offset+=100){var ids=new LinkedHashSet<String>();accepted.subList(offset,Math.min(offset+100,accepted.size())).forEach(member->ids.add(member.itemId()));captured.putAll(metadata.lockBatch(ids));}
            var eligibleIds=new ArrayList<String>();

            // News projection is not applicable to catalog/metric members; explicit unknown outcomes still fail.
            for(var member:universe.members()) {
                if("excluded".equals(member.terminalState())){entries.put(member.itemId(),new Entry(member.itemId(),Reason.COLLECTOR_EXCLUDED,null,null,null));continue;}
                var row=captured.get(member.itemId());
                if(row==null||!member.itemId().equals(row.id())||!universe.runId().equals(row.runId())||!universe.runId().equals(row.actualRun())
                        ||!universe.sourceId().equals(row.sourceId())||!universe.sourceId().equals(row.runSource())||!Objects.equals(member.sourceItemId(),row.sourceItemId())
                        ||!day.equals(row.day())||!category.equals(row.category())||!"accepted".equals(row.terminal())||row.payloadHash()==null
                        ||!Arrays.equals(HexFormat.of().parseHex(member.payloadHash()),row.payloadHash())||row.revision()<0||row.revision()>=CanonicalJson.MAX_SAFE_INTEGER
                        ||!"production".equals(row.activation())||!Set.of("complete","partial").contains(row.delivery())
                        ||!Set.of("pending","ready","needs_review","excluded").contains(row.state())
                        ||(row.projection()!=null&&!Set.of("initialized","unchanged","promoted","historical","needs_review").contains(row.projection()))
                        ||row.created()==null||row.finished()==null||row.created().getNano()%1000!=0||row.finished().getNano()%1000!=0)throw unavailable();
                Reason reason=Reason.ELIGIBLE;
                if(!row.valid()||Set.of("needs_review","excluded").contains(row.state())||"needs_review".equals(row.projection())
                        ||row.created().isAfter(cutoff)||row.finished().isAfter(cutoff)||row.targetDate()!=null&&!day.equals(row.targetDate()))reason=Reason.NOT_AI_ELIGIBLE;
                else if(row.entityId()!=null){WireValues.id(row.entityId(),false);if(!"ready".equals(row.state()))throw unavailable();reason=Reason.ALREADY_APPLIED;}
                else eligibleIds.add(member.itemId());
                if(reason!=Reason.ELIGIBLE)entries.put(member.itemId(),new Entry(member.itemId(),reason,null,null,null));
            }
            for(int offset=0;offset<eligibleIds.size();offset+=100){
                var batch=eligibleIds.subList(offset,Math.min(offset+100,eligibleIds.size()));var facts=evidence.read(Set.copyOf(batch),cutoff);var exact=new HashMap<String,SourceMemberEvidenceService.Fact>();
                for(var fact:facts)if(fact==null||exact.putIfAbsent(fact.id(),fact)!=null)throw unavailable();
                if(!exact.keySet().equals(Set.copyOf(batch)))throw unavailable();
                for(var id:batch){var row=captured.get(id);var fact=exact.get(id);
                    if(fact.revision()!=row.revision()||!category.equals(fact.category()))throw unavailable();
                    var payload=CanonicalJson.parse(fact.payloadJson());boolean metric=payload.has("subject");
                    // A catalogue capture is not an official publication time. Unknown times remain unknown.
                    var published=metric?null:payload.get("published_at");
                    boolean nextDay=published!=null&&!published.isNull()&&!WireValues.utcMicroseconds(published).isBefore(end);
                    var evaluated=metric?payload.get("evaluated_at"):null;
                    boolean futureEvaluation=evaluated!=null&&!evaluated.isNull()&&WireValues.utcMicroseconds(evaluated).isAfter(cutoff);
                    if(nextDay||futureEvaluation){entries.put(id,new Entry(id,Reason.NOT_AI_ELIGIBLE,null,null,null));continue;}
                    bytes+=fact.payloadJson().getBytes(StandardCharsets.UTF_8).length+fact.identityKey().getBytes(StandardCharsets.UTF_8).length
                        +fact.title().getBytes(StandardCharsets.UTF_8).length+(fact.summary()==null?0:fact.summary().getBytes(StandardCharsets.UTF_8).length);
                    if(bytes>32L*1024*1024)throw new CapacityGuarded();
                    Instant available=row.created().isAfter(row.finished())?row.created():row.finished();
                    entries.put(id,new Entry(id,Reason.ELIGIBLE,fact,row.day(),available));
                }
            }
            if(entries.size()!=universe.members().size())throw unavailable();return new Selection(universe.runId(),universe.sourceId(),category,day,cutoff,new ArrayList<>(entries.values()));
        }catch(CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw unavailable();}
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("complete catalog qualification unavailable");}
}
