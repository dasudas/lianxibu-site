package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** All native heads and full count events at the fixed daily cutoff. No ranking or citation authority. */
public final class EntityHeatCutoffProofService {
    public static final int MAX_ORIGINAL_BYTES=32*1024*1024;
    public record Count(EntityHeatSnapshot snapshot,String eventId,String eventHash,Instant availableAt){@Override public String toString(){return "ProvenEntityHeatCutoffCount[redacted]";}}
    public record Gap(String entityId,String reason){@Override public String toString(){return "EntityHeatCutoffGap[redacted]";}}
    private record Original(JdbcEntityHeatEvents.Event event,Instant first){ }
    private record Facts(List<JdbcEntityHeatCutoffUniverse.Head> heads,List<Original> events,Map<String,Count> counts,List<Gap> gaps,long originalBytes){ }
    public static final class Captured {
        private final Object issuer;private final Instant cutoff;private final Facts facts;
        private Captured(Object issuer,Instant cutoff,Facts facts){this.issuer=issuer;this.cutoff=cutoff;this.facts=facts;}
        public List<JdbcEntityHeatEvents.Event> originalEvents(){return facts.events().stream().map(Original::event).toList();}
        public Instant cutoff(){return cutoff;}public Map<String,Count> counts(){return facts.counts();}public List<Gap> gaps(){return facts.gaps();}public long originalByteSize(){return facts.originalBytes();}
        @Override public String toString(){return "CompleteEntityHeatCutoffFacts[redacted]";}
    }
    private final Object issuer=new Object();private final JdbcEntityHeatCutoffUniverse universe;private final EntityHeatEventProofService events;private final EntityHeatEventAvailabilityService availability;private final TransactionTemplate reads;private final int budget;
    public EntityHeatCutoffProofService(JdbcEntityHeatCutoffUniverse universe,EntityHeatEventProofService events,EntityHeatEventAvailabilityService availability,PlatformTransactionManager tx){this(universe,events,availability,tx,MAX_ORIGINAL_BYTES);}
    public EntityHeatCutoffProofService(JdbcEntityHeatCutoffUniverse universe,EntityHeatEventProofService events,EntityHeatEventAvailabilityService availability,PlatformTransactionManager tx,int budget){this.universe=Objects.requireNonNull(universe);this.events=Objects.requireNonNull(events);this.availability=Objects.requireNonNull(availability);if(budget<1024||budget>MAX_ORIGINAL_BYTES)throw invalid();this.budget=budget;reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public Captured capture(Instant cutoff){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();fixed(cutoff);return reads.execute(s->new Captured(issuer,cutoff,build(cutoff)));}
    /** Explicit supplement facts at the supplied microsecond cutoff. This grants no report generation or publication authority. */
    public Captured captureSupplement(Instant cutoff){
        if(TransactionSynchronizationManager.isActualTransactionActive()||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
        return reads.execute(s->new Captured(issuer,cutoff,build(cutoff)));
    }
    public Captured verifyObserved(Captured captured){readonly();if(captured==null||captured.issuer!=issuer)throw invalid();if(!captured.facts.equals(build(captured.cutoff)))throw invalid();return captured;}
    private Facts build(Instant cutoff){readonly();var heads=new TreeMap<String,JdbcEntityHeatCutoffUniverse.Head>();long bytes=0;String cursor="";
        while(true){var page=universe.heads(cursor,cutoff,64);for(var head:page.values()){if(heads.put(head.id(),head)!=null)throw invalid();bytes=add(bytes,256+size(head.id()));}if(!page.hasMore())break;cursor=page.values().getLast().id();}
        var ids=new TreeSet<String>();for(var head:heads.values())if("news_tech".equals(head.category()))ids.add(head.id());var candidates=new TreeMap<String,Count>();var blocked=new HashMap<String,String>();var originals=new ArrayList<Original>();cursor="";
        while(true){var page=universe.events(cursor,cutoff,64);for(String id:page.values()){
            var proof=events.readObserved(id);if(proof==null||proof.event().actedAt().isAfter(cutoff))throw invalid();var first=availability.readObserved(id);Instant at=first==null?null:first.availableAt();originals.add(new Original(proof.event(),at));bytes=add(bytes,(long)size(proof.event().payloadJson())+size(proof.event().targetJson())+size(proof.event().reason())+512);
            if(!proof.hasCompleteTouchedFormat()){var payload=EntityDecisionJdbcLog.decodeReceiptPayload(proof.event().payloadJson(),proof.event().reason());for(String touched:new String[]{payload.oldId(),payload.newId()})if(touched!=null&&(heads.get(touched)==null||"news_tech".equals(heads.get(touched).category()))){ids.add(touched);blocked.put(touched,"count_history_incomplete");}continue;}
            for(var snapshot:proof.snapshots()){bytes=add(bytes,size(snapshot.json()));var head=heads.get(snapshot.entityId());if(head!=null&&(head.revision()<snapshot.revision()||!head.category().equals(snapshot.category())))throw invalid();if(!"news_tech".equals(snapshot.category()))continue;ids.add(snapshot.entityId());if(at==null||at.isAfter(cutoff)){blocked.put(snapshot.entityId(),"count_unobserved_at_cutoff");continue;}
                var value=new Count(snapshot,id,JdbcEntityHeatAvailability.eventHash(proof.event()),at);var previous=candidates.get(snapshot.entityId());if(previous==null||snapshot.revision()>previous.snapshot().revision())candidates.put(snapshot.entityId(),value);else if(snapshot.revision()==previous.snapshot().revision()&&!snapshot.equals(previous.snapshot()))throw invalid();
            }
        }if(!page.hasMore())break;cursor=page.values().getLast();}
        var counts=new TreeMap<String,Count>();var gaps=new ArrayList<Gap>();for(String id:ids){var value=candidates.get(id);String reason=blocked.get(id);if(reason==null&&value==null)reason="count_missing";if(reason==null&&(value.snapshot().occurrenceCount()==null||value.snapshot().firstRecordedAt()==null))reason="count_unknown";if(reason==null&&value.snapshot().legacy())reason="count_legacy_unproven";if(reason==null)counts.put(id,value);else gaps.add(new Gap(id,reason));}
        return new Facts(List.copyOf(heads.values()),List.copyOf(originals),Collections.unmodifiableMap(counts),List.copyOf(gaps),bytes);
    }
    private long add(long current,long next){long value=Math.addExact(current,next);if(value>budget)throw invalid();return value;}
    private static int size(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
    private static void fixed(Instant cutoff){if(cutoff==null||cutoff.getNano()!=0||!cutoff.atZone(ZoneId.of("Asia/Shanghai")).toLocalTime().equals(LocalTime.of(5,0)))throw invalid();}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("complete original entity heat cutoff proof unavailable");}
}
