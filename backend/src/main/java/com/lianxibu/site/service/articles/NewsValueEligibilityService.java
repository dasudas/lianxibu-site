package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.articles.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Complete current P2 qualification. A caller must separately prove its committed P1 dependency. */
public final class NewsValueEligibilityService {
    public enum Reason { ELIGIBLE,COLLECTOR_EXCLUDED,NOT_AI_ELIGIBLE }
    public record Entity(String id,String identityKey,LocalDate firstSeenBusinessDate,boolean legacy,long revision,Instant metadataAsOf) {
        @Override public String toString(){return "ValueEntityMetadata[redacted]";}
    }
    public record Entry(String itemId,Reason reason,SourceMemberEvidenceService.Fact fact,LocalDate businessDate,Instant completedAt,Entity entity,boolean eligibleHighValue) {
        public Entry {
            WireValues.id(itemId,false);Objects.requireNonNull(reason);
            if(reason==Reason.ELIGIBLE?fact==null||businessDate==null||completedAt==null||entity==null||!itemId.equals(fact.id())||completedAt.getNano()%1000!=0
                    :fact!=null||businessDate!=null||completedAt!=null||entity!=null||eligibleHighValue)throw unavailable();
            if(eligibleHighValue&&(entity.legacy()||!businessDate.equals(entity.firstSeenBusinessDate())))throw unavailable();
        }
        @Override public String toString(){return "ValueQualification[redacted]";}
    }
    public record Selection(String runId,String sourceId,LocalDate day,Instant cutoff,List<Entry> entries) {
        public Selection {entries=List.copyOf(entries);}
        @Override public String toString(){return "NewsValueSelection[redacted]";}
    }
    public static final class CapacityGuarded extends RuntimeException {public CapacityGuarded(){super("capacity_guard");}}
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    private final JdbcNewsDedupEligibility metadata;private final JdbcNewsValueEntities entities;
    private final SourceMemberEvidenceService evidence;private final Clock clock;private final long maximumBytes;
    public NewsValueEligibilityService(JdbcNewsDedupEligibility metadata,JdbcNewsValueEntities entities,SourceMemberEvidenceService evidence,Clock clock) {
        this(metadata,entities,evidence,clock,32L*1024*1024);
    }
    NewsValueEligibilityService(JdbcNewsDedupEligibility metadata,JdbcNewsValueEntities entities,SourceMemberEvidenceService evidence,Clock clock,long maximumBytes) {
        this.metadata=Objects.requireNonNull(metadata);this.entities=Objects.requireNonNull(entities);this.evidence=Objects.requireNonNull(evidence);this.clock=Objects.requireNonNull(clock);
        if(maximumBytes<1||maximumBytes>32L*1024*1024)throw unavailable();this.maximumBytes=maximumBytes;
    }
    public Selection capture(MemberUniverse.Universe universe,LocalDate day) {
        try {
            requireWriter();return selectCurrent(universe,day,clock.instant().truncatedTo(ChronoUnit.MICROS));
        }catch(CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw unavailable();}
    }
    /** Comparison only: current rows must still satisfy the original capture, including its cutoff. */
    public boolean matchesCurrent(MemberUniverse.Universe universe,Selection original) {
        try {
            requireWriter();
            if(original==null||original.cutoff()==null||clock.instant().truncatedTo(ChronoUnit.MICROS).isBefore(original.cutoff()))throw unavailable();
            return original.equals(selectCurrent(universe,original.day(),original.cutoff()));
        }catch(CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw unavailable();}
    }
    private static void requireWriter(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw unavailable();}
    private Selection selectCurrent(MemberUniverse.Universe universe,LocalDate day,Instant cutoff) {
        try {
            if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||universe==null||day==null)throw unavailable();
            Instant start=day.atStartOfDay(SHANGHAI).toInstant(),end=day.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
            if(cutoff.isBefore(end))throw unavailable();var entries=new TreeMap<String,Entry>();long bytes=0;
            var members=universe.members();var currentEntities=new HashMap<String,Entity>();
            for(int offset=0;offset<members.size();offset+=100){
                var batch=members.subList(offset,Math.min(offset+100,members.size()));var ids=new TreeSet<String>();
                for(var member:batch)if(!"excluded".equals(member.terminalState()))ids.add(member.itemId());
                var flagRows=ids.isEmpty()?Map.<String,JdbcNewsValueEntities.MemberFlags>of():entities.memberFlagsBatch(ids);
                var metadataRows=ids.isEmpty()?Map.<String,JdbcNewsDedupEligibility.Row>of():metadata.lockBatch(ids);
                var evidenceIds=new TreeSet<String>();var batchEntityIds=new TreeSet<String>();
                for(var row:metadataRows.values())if(row.valid()&&"ready".equals(row.state())&&!"needs_review".equals(row.projection())&&row.entityId()!=null
                        &&row.created()!=null&&!row.created().isAfter(cutoff)&&row.finished()!=null&&!row.finished().isAfter(cutoff)&&(row.targetDate()==null||day.equals(row.targetDate()))){
                    WireValues.id(row.entityId(),false);evidenceIds.add(row.id());batchEntityIds.add(row.entityId());
                }
                currentEntities.keySet().retainAll(batchEntityIds);
                var newEntityIds=new TreeSet<>(batchEntityIds);newEntityIds.removeAll(currentEntities.keySet());
                if(!newEntityIds.isEmpty()){
                    var rawEntities=entities.entitiesBatch(newEntityIds);for(var id:newEntityIds)currentEntities.put(id,entity(rawEntities.get(id),id,cutoff));
                }
                var currentFacts=new HashMap<String,SourceMemberEvidenceService.Fact>();
                if(!evidenceIds.isEmpty())for(var fact:evidence.read(evidenceIds,cutoff))if(!evidenceIds.contains(fact.id())||currentFacts.putIfAbsent(fact.id(),fact)!=null)throw unavailable();
                if(currentFacts.size()!=evidenceIds.size())throw unavailable();
            for(var original:batch) {
                if("excluded".equals(original.terminalState())){entries.put(original.itemId(),excluded(original.itemId(),Reason.COLLECTOR_EXCLUDED));continue;}
                var flags=flagRows.get(original.itemId());var row=metadataRows.get(original.itemId());
                if(flags==null||row==null||!original.itemId().equals(flags.id())||flags.valid()==null||flags.valid()!=0&&flags.valid()!=1||flags.revision()==null
                        ||flags.revision()<0||flags.revision()>=CanonicalJson.MAX_SAFE_INTEGER||flags.revision()!=row.revision()||row.valid()!=(flags.valid()==1)
                        ||!original.itemId().equals(row.id())||!universe.runId().equals(row.runId())||!universe.runId().equals(row.actualRun())
                        ||!universe.sourceId().equals(row.sourceId())||!universe.sourceId().equals(row.runSource())||!Objects.equals(original.sourceItemId(),row.sourceItemId())
                        ||!day.equals(row.day())||!"news_tech".equals(row.category())||!"accepted".equals(row.terminal())||row.payloadHash()==null
                        ||!Arrays.equals(HexFormat.of().parseHex(original.payloadHash()),row.payloadHash())||!"production".equals(row.activation())||!Set.of("complete","partial").contains(row.delivery())
                        ||!Set.of("pending","ready","needs_review","excluded").contains(row.state())||!Set.of("initialized","unchanged","promoted","historical","needs_review").contains(row.projection())
                        ||row.created()==null||row.finished()==null||row.created().getNano()%1000!=0||row.finished().getNano()%1000!=0)throw unavailable();
                if(!row.valid()||!"ready".equals(row.state())||"needs_review".equals(row.projection())||row.entityId()==null
                        ||row.created().isAfter(cutoff)||row.finished().isAfter(cutoff)||row.targetDate()!=null&&!day.equals(row.targetDate())) {
                    entries.put(original.itemId(),excluded(original.itemId(),Reason.NOT_AI_ELIGIBLE));continue;
                }
                WireValues.id(row.entityId(),false);var entity=currentEntities.get(row.entityId());var fact=currentFacts.get(original.itemId());if(entity==null||fact==null)throw unavailable();
                if(!original.itemId().equals(fact.id())||fact.revision()!=row.revision()||!"news_tech".equals(fact.category()))throw unavailable();
                var body=CanonicalJson.parse(fact.payloadJson());String basis=WireValues.text(body.get("time_basis"));
                var rawTime=switch(basis){case "published","updated","submitted"->body.get(basis+"_at");default->null;};Instant sourceTime=rawTime==null||rawTime.isNull()?null:WireValues.utcMicroseconds(rawTime);
                if(sourceTime==null||sourceTime.isBefore(start)||!sourceTime.isBefore(end)){entries.put(original.itemId(),excluded(original.itemId(),Reason.NOT_AI_ELIGIBLE));continue;}
                var material=NewsValueMaterial.from(universe.sourceId(),fact);
                bytes+=fact.payloadJson().getBytes(StandardCharsets.UTF_8).length+fact.identityKey().getBytes(StandardCharsets.UTF_8).length+fact.title().getBytes(StandardCharsets.UTF_8).length
                        +(fact.summary()==null?0:fact.summary().getBytes(StandardCharsets.UTF_8).length)+entity.identityKey().getBytes(StandardCharsets.UTF_8).length;
                if(bytes>maximumBytes)throw new CapacityGuarded();
                boolean high=day.equals(entity.firstSeenBusinessDate())&&!entity.legacy()&&!"missing".equals(material.inputBasis());
                entries.put(original.itemId(),new Entry(original.itemId(),Reason.ELIGIBLE,fact,day,row.created().isAfter(row.finished())?row.created():row.finished(),entity,high));
            }
            }
            if(entries.size()!=universe.members().size())throw unavailable();return new Selection(universe.runId(),universe.sourceId(),day,cutoff,new ArrayList<>(entries.values()));
        }catch(CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw unavailable();}
    }
    private Entity entity(JdbcNewsValueEntities.Entity row,String id,Instant cutoff) {
        if(row==null||!id.equals(row.id())||!"news_tech".equals(row.category())||row.identityKey()==null||row.identityHash()==null
                ||!row.identityKey().equals(new String(CanonicalJson.bytes(CanonicalJson.parse(row.identityKey())),StandardCharsets.UTF_8))
                ||!Arrays.equals(row.identityHash(),JdbcEntityDedup.identityHash(row.category(),row.identityKey()))||row.legacy()==null||row.legacy()!=0&&row.legacy()!=1
                ||row.revision()==null||row.revision()<0||row.revision()>=CanonicalJson.MAX_SAFE_INTEGER||row.firstRecordedAt()==null||row.firstRecordedAt().getNano()%1000!=0||row.firstRecordedAt().isAfter(cutoff)
                ||row.legacy()==0&&row.firstSeenBusinessDate()==null||row.firstSeenBusinessDate()!=null&&row.firstSeenBusinessDate().isAfter(cutoff.atZone(SHANGHAI).toLocalDate()))throw unavailable();
        var identity=CanonicalJson.parse(row.identityKey());WireValues.object(identity,Set.of("namespace","key"),Set.of("namespace","key"));WireValues.text(identity.get("namespace"));WireValues.text(identity.get("key"));
        return new Entity(id,row.identityKey(),row.firstSeenBusinessDate(),row.legacy()==1,row.revision(),cutoff);
    }
    private static Entry excluded(String id,Reason reason){return new Entry(id,reason,null,null,null,null,false);}
    private static IllegalStateException unavailable(){return new IllegalStateException("complete current news value qualification unavailable");}
}
