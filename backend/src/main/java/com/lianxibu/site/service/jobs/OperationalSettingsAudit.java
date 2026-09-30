package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import com.lianxibu.site.service.settings.SettingsJdbcStore.OperationalAudit;
import com.lianxibu.site.service.settings.SettingsJdbcStore.OperationalChange;
import com.lianxibu.site.service.settings.SettingsService.OperationalRule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Jobs owns the append-only audit; settings supplies immutable controlled facts. */
public final class OperationalSettingsAudit implements OperationalAudit {
    private final JdbcJobRecords jobs;
    public OperationalSettingsAudit(JdbcJobRecords jobs){if(jobs==null)throw new IllegalArgumentException("audit persistence required");this.jobs=jobs;}
    @Override public void append(OperationalChange change){
        if(change==null||change.previousSettingsRevision()<0||change.settingsRevision()!=change.previousSettingsRevision()+1
                ||change.operationalRevision()<1||change.settingsRevision()>CanonicalJson.MAX_SAFE_INTEGER
                ||change.operationalRevision()>CanonicalJson.MAX_SAFE_INTEGER||change.actionKey()==null||!change.actionKey().matches("[!-~]{1,128}")
                ||change.actorId()==null||change.actorId().isBlank()||change.actorId().codePointCount(0,change.actorId().length())>64
                ||change.reason()==null||change.reason().isBlank()||change.reason().codePointCount(0,change.reason().length())>2048
                ||change.before().size()>20||change.after().size()>20||change.at()==null)throw new IllegalArgumentException("invalid operational settings change");
        String keyHash=hash(change.actorId()+"\n"+change.actionKey());
        ObjectNode target=OperationalStateCodec.JSON.createObjectNode();target.put("scope","operational_limits-v1");
        ObjectNode payload=OperationalStateCodec.JSON.createObjectNode();payload.put("record_type","ops_configuration_change");
        payload.put("previous_settings_revision",change.previousSettingsRevision());payload.put("settings_revision",change.settingsRevision());
        payload.put("operational_revision",change.operationalRevision());payload.set("before",rules(change.before()));payload.set("after",rules(change.after()));
        // Only references/configuration are retained; no credentials, addresses or prompts are added.
        jobs.appendDisposition(new JdbcJobRecords.Disposition("opsc_"+keyHash.substring(0,48),"ops_config:"+keyHash,
                "ops_rule",target.toString(),change.actorId(),change.reason(),payload.toString()),change.at());
    }
    private static ArrayNode rules(List<OperationalRule> values){
        ArrayNode array=OperationalStateCodec.JSON.createArrayNode();for(OperationalRule rule:values){
            ObjectNode row=OperationalStateCodec.JSON.createObjectNode();row.put("rule_id",rule.ruleId());row.put("warning_threshold",rule.warningThreshold());
            row.put("critical_threshold",rule.criticalThreshold());row.put("recovery_threshold",rule.recoveryThreshold());
            OperationalStateCodec.put(row,"notification_secret_ref",rule.notificationSecretRef());row.put("notification_configured",rule.notificationConfigured());
            if(rule.ruleParameters()!=null){ObjectNode parameters=OperationalStateCodec.JSON.createObjectNode();new java.util.TreeMap<>(rule.ruleParameters()).forEach(parameters::put);row.set("rule_parameters",parameters);}
            array.add(row);
        }return array;
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
