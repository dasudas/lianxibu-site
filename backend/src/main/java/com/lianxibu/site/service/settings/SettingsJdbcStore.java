package com.lianxibu.site.service.settings;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.settings.JdbcSettingsWrites;
import com.lianxibu.site.service.settings.SettingsService.*;
import com.lianxibu.site.service.settings.SettingsUpdateService.Current;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/** Persistent settings consumer with a caller-injected audit port; no reverse jobs dependency. */
public final class SettingsJdbcStore implements SettingsUpdateService.Store {
    public record OperationalConfiguration(long revision,List<OperationalRule> rules,boolean ready){
        public OperationalConfiguration{rules=List.copyOf(rules);}
    }
    public record OperationalChange(long previousSettingsRevision,long settingsRevision,long operationalRevision,
                                    List<OperationalRule> before,List<OperationalRule> after,
                                    String actionKey,String actorId,String reason,Instant at){
        public OperationalChange{before=List.copyOf(before);after=List.copyOf(after);}
    }
    @FunctionalInterface public interface OperationalAudit{void append(OperationalChange change);}
    private final JdbcSettingsWrites store;
    private final Predicate<String> publishedPackage;
    private final OperationalAudit audit;
    private final Clock clock;
    public SettingsJdbcStore(JdbcSettingsWrites store,Predicate<String> publishedPackage,OperationalAudit audit,Clock clock){
        if(store==null||publishedPackage==null||audit==null||clock==null)throw new IllegalArgumentException("persistent settings dependencies required");
        this.store=store;this.publishedPackage=publishedPackage;this.audit=audit;this.clock=clock;
    }
    @Override public Preferences committed(String key,String hash){
        var receipt=store.receipt(key);if(receipt==null)return null;
        if(!hash.equals(receipt.commandHash()))throw new SettingsUpdateService.Rejected("identity_conflict");
        return SettingsPersistenceCodec.preferences(receipt.responseJson());
    }
    @Override public Current current(){
        return decode(store.read());
    }
    private Current decode(JdbcSettingsWrites.Snapshot snapshot){
        var row=snapshot.preferences();
        if(row==null)return new Current(0,null,null,false,null,List.of(),false);
        JsonNode value;
        try{value=CanonicalJson.parse(row.valueJson());if(!value.isObject())throw new IllegalArgumentException();}
        catch(IllegalArgumentException invalid){return new Current(row.revision(),null,null,false,null,List.of(),false);}
        List<OperationalRule> operational;
        try{operational=snapshot.operational()!=null?SettingsPersistenceCodec.operational(snapshot.operational().valueJson())
                :value.has("operational_limits")?SettingsPersistenceCodec.rules(value.get("operational_limits")):List.of();}
        catch(IllegalArgumentException missing){return new Current(row.revision(),null,null,false,null,List.of(),false);}
        if(!value.path("mapping_version").isTextual()||!"1".equals(value.path("mapping_version").asText()))
            return new Current(row.revision(),null,null,legacyPause(value),null,operational,false);
        try{
            long operationalRevision=snapshot.operational()==null?0:snapshot.operational().revision();
            if(SettingsPersistenceCodec.number(value.get("operational_config_revision"))!=operationalRevision)throw new IllegalArgumentException();
            return new Current(row.revision(),SettingsPersistenceCodec.keywords(value.get("follow_keywords")),
                    SettingsPersistenceCodec.keywords(value.get("blocked_keywords")),SettingsPersistenceCodec.flag(value.get("ai_paused")),
                    SettingsPersistenceCodec.optionalId(value.get("default_package_id")),operational,true);
        }catch(IllegalArgumentException missing){return new Current(row.revision(),null,null,false,null,operational,false);}
    }
    public OperationalConfiguration operationalConfiguration(){
        var snapshot=store.read();if(snapshot.operational()==null)return new OperationalConfiguration(0,List.of(),false);
        List<OperationalRule> rules;
        try{rules=SettingsPersistenceCodec.operational(snapshot.operational().valueJson());}
        catch(IllegalArgumentException invalid){return new OperationalConfiguration(snapshot.operational().revision(),List.of(),false);}
        var ids=new HashSet<String>();boolean ready=rules.size()==10&&decode(snapshot).mappingComplete();
        for(OperationalRule rule:rules)ready&=ids.add(rule.ruleId())&&OperationalRulePolicy.ready(rule);
        for(int id=1;id<=10;id++)ready&=ids.contains(String.format(java.util.Locale.ROOT,"OPS-%02d",id));
        return new OperationalConfiguration(snapshot.operational().revision(),rules,ready);
    }
    @Override public void update(Current previous,PreferencesWrite command,String hash,String key,String actor){
        var snapshot=store.locked();long oldOps=snapshot.operational()==null?0:snapshot.operational().revision();
        boolean opsChanged=!previous.operationalLimits().equals(command.operationalLimits());long nextOps=opsChanged?oldOps+1:oldOps;
        try{store.update(previous.revision(),oldOps,SettingsPersistenceCodec.patch(command,nextOps).toString(),
                opsChanged?SettingsPersistenceCodec.operational(command.operationalLimits()):null,clock.instant());}
        catch(JdbcSettingsWrites.Conflict conflict){throw new SettingsUpdateService.Rejected("revision_conflict");}
    }
    @Override public void complete(Preferences result,String hash,String key,String actor){store.receipt(key,hash,actor,SettingsPersistenceCodec.preferences(result).toString(),clock.instant());}
    @Override public void recordNoop(Current previous,String hash,String key,String actor){
        complete(new Preferences(previous.revision(),previous.followKeywords(),previous.blockedKeywords(),previous.aiPaused(),previous.defaultPackageId(),previous.operationalLimits()),hash,key,actor);
    }
    @Override public void recordOperationalChange(Current previous,PreferencesWrite command,String key,String actor){
        var current=store.read();audit.append(new OperationalChange(previous.revision(),current.preferences().revision(),current.operational().revision(),
                previous.operationalLimits(),command.operationalLimits(),key,actor,command.operationalChangeReason(),clock.instant()));
    }
    @Override public boolean publishedPackage(String id){return publishedPackage.test(id);}
    @Override public void recordPauseIntent(boolean paused){var current=store.read();store.pauseIntent(paused,current.preferences().revision(),clock.instant());}
    private static boolean legacyPause(JsonNode value){JsonNode flag=value.get("pause_new_jobs");return flag!=null&&(flag.isBoolean()&&flag.booleanValue()||flag.isIntegralNumber()&&flag.asInt()==1);}
}
