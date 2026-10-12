package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcEntityHeatEvents;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Native original event facts only. First commit visibility, all entities and exact report cutoff remain separate. */
public final class EntityHeatEventProofService {
    public static final class Proven {
        private final Object issuer;private final JdbcEntityHeatEvents.Event event;private final EntityDecisionJdbcLog.Payload payload;
        private Proven(Object issuer,JdbcEntityHeatEvents.Event event,EntityDecisionJdbcLog.Payload payload){this.issuer=issuer;this.event=event;this.payload=payload;}
        public JdbcEntityHeatEvents.Event event(){return event;}public List<EntityHeatSnapshot> snapshots(){return payload.snapshots();}public boolean hasCompleteTouchedFormat(){return "entity-decision-v2".equals(payload.schema());}public String operation(){return payload.operation();}public String itemId(){return payload.itemId();}
        @Override public String toString(){return "OriginalEntityHeatEventProof[redacted]";}
    }
    private final Object issuer=new Object();private final JdbcEntityHeatEvents events;private final TransactionTemplate reads;
    public EntityHeatEventProofService(JdbcEntityHeatEvents events,PlatformTransactionManager tx){this.events=Objects.requireNonNull(events);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public Proven find(String id){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();return reads.execute(s->readObserved(id));}
    public Proven readObserved(String id){readonly();var event=events.read(id);if(event==null)return null;var payload=EntityDecisionJdbcLog.decodeReceiptPayload(event.payloadJson(),event.reason());var target=CanonicalJson.parse(event.targetJson());var fields=Set.of("kind","id");WireValues.object(target,fields,fields);if(!"source_run_item".equals(WireValues.text(target.get("kind")))||!payload.itemId().equals(WireValues.id(target.get("id"),false)))throw invalid();for(var snapshot:payload.snapshots())if(snapshot.firstRecordedAt()!=null&&snapshot.firstRecordedAt().isAfter(event.actedAt()))throw invalid();return new Proven(issuer,event,payload);}
    public Proven verifyObserved(Proven proven){readonly();if(proven==null||proven.issuer!=issuer)throw invalid();var original=readObserved(proven.event.id());if(original==null||!original.event.equals(proven.event)||!original.payload.equals(proven.payload))throw invalid();return proven;}
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original entity heat event proof unavailable");}
}
