package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.settings.OperationalRulePolicy;
import com.lianxibu.site.service.settings.SettingsJdbcStore.OperationalConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Reads one controlled snapshot; only gates new input, never completion or receipt replay. */
public final class OperationalAdmissionService {
    public enum Operation { NEW_RUN, PREPARE_ANALYSIS, DISPATCH_AI, UPLOAD_OBSERVATION }
    public enum Reason { ALLOWED, CONFIGURATION_UNKNOWN, TELEMETRY_UNKNOWN, CAPACITY_GUARD, OPERATIONAL_GUARD }
    public record Decision(boolean allowed,Reason reason) { }
    public record Snapshot(OperationalConfiguration configuration,List<State> states){
        public Snapshot { states=List.copyOf(states); }
    }
    @FunctionalInterface public interface SnapshotSource { Snapshot current(); }
    /** Resource identities come from controlled server assembly, never request parameters. */
    public record Scopes(List<Key> capacity,List<Key> dispatch,Map<String,List<Key>> sources){
        public Scopes {
            capacity=List.copyOf(capacity);dispatch=List.copyOf(dispatch);
            var copied=new HashMap<String,List<Key>>();sources.forEach((key,value)->copied.put(key,List.copyOf(value)));sources=Map.copyOf(copied);
            if(capacity.size()!=2||!ids(capacity).equals(Set.of("OPS-04","OPS-10"))
                    ||dispatch.size()!=3||!ids(dispatch).equals(Set.of("OPS-03","OPS-06","OPS-08"))||sources.size()>128)
                throw new IllegalArgumentException("complete controlled operational scopes required");
            sources.forEach((id,keys)->{if(id==null||id.isBlank()||id.length()>80||keys.size()!=1||!ids(keys).equals(Set.of("OPS-07")))throw new IllegalArgumentException("invalid source operational scope");});
        }
        private static Set<String> ids(List<Key> keys){var ids=new HashSet<String>();for(Key key:keys)if(key==null||!ids.add(key.ruleId()))throw new IllegalArgumentException("invalid duplicate scope");return ids;}
    }
    private final SnapshotSource source;private final Scopes scopes;private final Clock clock;
    public OperationalAdmissionService(SnapshotSource source,Scopes scopes,Clock clock){
        if(source==null||scopes==null||clock==null)throw new IllegalArgumentException("operational admission dependencies required");
        this.source=source;this.scopes=scopes;this.clock=clock;
    }
    public Decision check(Operation operation,String sourceId){
        if(operation==null)throw new IllegalArgumentException("operation required");
        Snapshot snapshot=source.current();
        if(snapshot==null||snapshot.configuration()==null||!snapshot.configuration().ready()||snapshot.configuration().revision()<1)return denied(Reason.CONFIGURATION_UNKNOWN);
        var configuration=snapshot.configuration();var rules=new HashMap<String,OperationalRulePolicy.Compiled>();
        try{for(var rule:configuration.rules())if(rules.put(rule.ruleId(),OperationalRulePolicy.compile(rule))!=null)return denied(Reason.CONFIGURATION_UNKNOWN);}
        catch(IllegalArgumentException invalid){return denied(Reason.CONFIGURATION_UNKNOWN);}
        if(rules.size()!=10)return denied(Reason.CONFIGURATION_UNKNOWN);
        for(int id=1;id<=10;id++)if(!rules.containsKey(String.format(Locale.ROOT,"OPS-%02d",id)))return denied(Reason.CONFIGURATION_UNKNOWN);
        var keys=new ArrayList<>(scopes.capacity());
        if(operation==Operation.DISPATCH_AI)keys.addAll(scopes.dispatch());
        if(operation==Operation.NEW_RUN){var specific=scopes.sources().get(sourceId);if(specific==null)return denied(Reason.CONFIGURATION_UNKNOWN);keys.addAll(specific);}
        var states=new HashMap<Key,State>();for(State state:snapshot.states())if(state==null||states.put(state.key(),state)!=null)return denied(Reason.TELEMETRY_UNKNOWN);
        Instant now=clock.instant();long maximumAge=rules.get("OPS-10").seconds("capacity_unknown_guard_seconds");
        // Capacity guards have precedence even if another required resource is missing.
        for(Key key:scopes.capacity()){State state=states.get(key);if(state!=null&&!state.guards().isEmpty())return denied(Reason.CAPACITY_GUARD);}
        for(Key key:keys){
            State state=states.get(key);if(state==null||state.configurationRevision()!=configuration.revision()
                    ||state.lastSampleAt()==null||state.lastSampleAt().isAfter(now)||now.isAfter(state.lastSampleAt().plusSeconds(maximumAge))
                    ||state.status()==Status.CONFIGURATION_UNKNOWN||state.status()==Status.TELEMETRY_UNKNOWN||state.observedValue()==null)
                return denied(Reason.TELEMETRY_UNKNOWN);
            Guard guard=switch(operation){case NEW_RUN->Guard.NEW_RUN;case PREPARE_ANALYSIS->Guard.PREPARE_ANALYSIS;case DISPATCH_AI->Guard.DISPATCH_AI;case UPLOAD_OBSERVATION->Guard.UPLOAD_OBSERVATION;};
            if(state.guards().contains(guard)||(operation==Operation.NEW_RUN&&state.guards().contains(Guard.NEW_FETCH)))return denied(Reason.OPERATIONAL_GUARD);
        }
        return new Decision(true,Reason.ALLOWED);
    }
    private static Decision denied(Reason reason){return new Decision(false,reason);}
}
