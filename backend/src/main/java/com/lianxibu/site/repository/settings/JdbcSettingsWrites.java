package com.lianxibu.site.repository.settings;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Versioned settings provider. Caller owns the shared transaction and business validation. */
public final class JdbcSettingsWrites {
    public record Setting(String key,long revision,String valueJson) { }
    public record Snapshot(Setting preferences,Setting operational,Setting pauseIntent) { }
    public record Receipt(String commandHash,String responseJson) { }
    public static final class Conflict extends RuntimeException {
        public Conflict(){super("settings revision or receipt conflict");}
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> PATCH=Set.of("mapping_version","follow_keywords","blocked_keywords","ai_paused",
            "pause_new_jobs","default_package_id","operational_limits","operational_config_revision");
    private static final String PRINCIPAL="owner-preferences", OPERATION="preferences-v1", SCOPE="settings/preferences-v1";
    private final JdbcTemplate jdbc;
    public JdbcSettingsWrites(DataSource source){if(source==null)throw new IllegalArgumentException("settings data source required");jdbc=new JdbcTemplate(source);}
    public Snapshot read(){return select(false);}
    public Snapshot locked(){requireTx();return select(true);}
    private Snapshot select(boolean lock){
        Map<String,Setting> values=new HashMap<>();
        jdbc.query("SELECT setting_key,revision,value_json FROM runtime_settings WHERE setting_key IN (?,?,?)"
                        +" ORDER BY setting_key"+(lock?" FOR UPDATE":""),rs->{
                    values.put(rs.getString(1),new Setting(rs.getString(1),rs.getLong(2),rs.getString(3)));},
                "preferences","operational_limits-v1","pause_intent-v1");
        return new Snapshot(values.get("preferences"),values.get("operational_limits-v1"),values.get("pause_intent-v1"));
    }
    /** Merge only mapped fields; legacy raw and unknown fields are preserved as unchanged values. */
    public long update(long expectedPreferenceRevision,long expectedOperationalRevision,
                       String mappedPatchJson,String newOperationalJson,Instant now){
        requireTx();
        if(expectedPreferenceRevision<0||expectedPreferenceRevision>=CanonicalJson.MAX_SAFE_INTEGER
                ||expectedOperationalRevision<0||expectedOperationalRevision>=CanonicalJson.MAX_SAFE_INTEGER||now==null)
            throw new IllegalArgumentException("invalid settings revision");
        Snapshot before=locked();
        if(revision(before.preferences())!=expectedPreferenceRevision||revision(before.operational())!=expectedOperationalRevision)throw new Conflict();
        JsonNode patch=object(mappedPatchJson);for(var field:patch.properties())if(!PATCH.contains(field.getKey()))throw new IllegalArgumentException("unknown mapped preference field");
        ObjectNode merged=before.preferences()==null?JSON.createObjectNode():(ObjectNode)object(before.preferences().valueJson()).deepCopy();
        for(var field:patch.properties())merged.set(field.getKey(),field.getValue());
        String mergedJson=merged.toString();object(mergedJson);
        if(newOperationalJson!=null)object(newOperationalJson);
        // Existing settings were locked in key order before any writes.
        if(newOperationalJson!=null)put("operational_limits-v1",expectedOperationalRevision,newOperationalJson,now);
        return put("preferences",expectedPreferenceRevision,mergedJson,now);
    }
    public void pauseIntent(boolean paused,long preferencesRevision,Instant now){
        requireTx();Snapshot current=locked();
        if(current.preferences()==null||current.preferences().revision()!=preferencesRevision)throw new Conflict();
        ObjectNode value=JSON.createObjectNode();value.put("schema_version","1");value.put("paused",paused);value.put("preferences_revision",preferencesRevision);
        put("pause_intent-v1",revision(current.pauseIntent()),value.toString(),now);
    }
    public Receipt receipt(String key){
        validKey(key);List<Receipt> values=jdbc.query("SELECT request_hash,response_json FROM request_receipts"
                        +" WHERE principal=? AND operation=? AND request_key=? AND receipt_kind='admin_action'",
                (rs,ignored)->new Receipt(HexFormat.of().formatHex(rs.getBytes(1)),rs.getString(2)),PRINCIPAL,OPERATION,key);
        if(values.size()>1)throw new Conflict();return values.isEmpty()?null:values.getFirst();
    }
    public void receipt(String key,String commandHash,String actorId,String responseJson,Instant now){
        requireTx();validKey(key);
        if(commandHash==null||!commandHash.matches("[0-9a-f]{64}")||actorId==null||actorId.isBlank()
                ||actorId.codePointCount(0,actorId.length())>64||!actorId.matches("[^\\x00-\\x1f\\x7f]+")||now==null)throw new IllegalArgumentException("invalid settings receipt");
        object(responseJson);Receipt old=receipt(key);
        if(old!=null){if(!commandHash.equals(old.commandHash())||!CanonicalJson.parse(old.responseJson()).equals(CanonicalJson.parse(responseJson)))throw new Conflict();return;}
        byte[] keyHash=sha(key), requestHash=HexFormat.of().parseHex(commandHash);
        jdbc.update("INSERT INTO request_receipts(principal,operation,request_key,request_hash,status_code,response_json,expires_at,"
                        +"id,receipt_kind,actor_id,scope,key_hash,original_status,current_status,created_at,revision,request_key_raw,business_hash)"
                        +" VALUES(?,?,?,?,200,?,NULL,?,'admin_action',?,?,?,'applied','applied',?,1,?,?)",
                PRINCIPAL,OPERATION,key,requestHash,responseJson,"prefs_"+HexFormat.of().formatHex(sha(actorId+"\n"+key)).substring(0,48),
                actorId,SCOPE,keyHash,Timestamp.from(now),key,requestHash);
    }
    private long put(String key,long expected,String json,Instant now){
        object(json);if(now==null||expected<0||expected>=CanonicalJson.MAX_SAFE_INTEGER)throw new IllegalArgumentException("invalid settings revision");
        if(expected==0)jdbc.update("INSERT INTO runtime_settings(setting_key,value_json,revision,updated_at) VALUES(?,?,1,?)",key,json,Timestamp.from(now));
        else if(jdbc.update("UPDATE runtime_settings SET value_json=?,revision=revision+1,updated_at=? WHERE setting_key=? AND revision=?",json,Timestamp.from(now),key,expected)!=1)throw new Conflict();
        return expected+1;
    }
    private static JsonNode object(String json){
        if(json==null||json.getBytes(StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("settings JSON exceeds bound");
        JsonNode value=CanonicalJson.parse(json);if(!value.isObject())throw new IllegalArgumentException("settings object required");return value;
    }
    private static long revision(Setting value){return value==null?0:value.revision();}
    private static void validKey(String key){if(key==null||!key.matches("[!-~]{1,128}"))throw new IllegalArgumentException("invalid action key");}
    private static byte[] sha(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("settings write requires shared transaction");}
}
