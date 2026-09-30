package com.lianxibu.site.service.settings;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.settings.SettingsService.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Closed mapped settings values; old raw fields stay in the repository's merged object. */
final class SettingsPersistenceCodec {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static ArrayNode rules(List<OperationalRule> rules){
        ArrayNode out=JSON.createArrayNode();for(OperationalRule rule:rules){
            ObjectNode v=JSON.createObjectNode();v.put("rule_id",rule.ruleId());v.put("warning_threshold",rule.warningThreshold());
            v.put("critical_threshold",rule.criticalThreshold());v.put("recovery_threshold",rule.recoveryThreshold());
            nullable(v,"notification_secret_ref",rule.notificationSecretRef());v.put("notification_configured",rule.notificationConfigured());
            if(rule.ruleParameters()!=null){ObjectNode map=JSON.createObjectNode();new java.util.TreeMap<>(rule.ruleParameters()).forEach(map::put);v.set("rule_parameters",map);}out.add(v);
        }return out;
    }
    static List<OperationalRule> rules(JsonNode node){
        if(node==null||!node.isArray()||node.size()>20)throw invalid();List<OperationalRule> values=new ArrayList<>();
        Set<String> required=Set.of("rule_id","warning_threshold","critical_threshold","recovery_threshold","notification_secret_ref","notification_configured");
        for(JsonNode row:node){if(!row.isObject())throw invalid();for(String field:required)if(!row.has(field))throw invalid();
            for(var field:row.properties())if(!required.contains(field.getKey())&&!"rule_parameters".equals(field.getKey()))throw invalid();
            Map<String,String> parameters=null;if(row.has("rule_parameters")){
                JsonNode map=row.get("rule_parameters");if(!map.isObject()||map.size()>32)throw invalid();parameters=new HashMap<>();
                for(var field:map.properties()){if(!field.getKey().matches("[a-z][a-z0-9_]{0,63}"))throw invalid();String value=text(field.getValue(),1,30);
                    if(!value.matches("(0|[1-9][0-9]*)(\\.[0-9]{1,8})?"))throw invalid();parameters.put(field.getKey(),value);}
            }
            values.add(new OperationalRule(text(row.get("rule_id"),1,64),decimal(row.get("warning_threshold")),decimal(row.get("critical_threshold")),
                    decimal(row.get("recovery_threshold")),row.get("notification_secret_ref").isNull()?null:text(row.get("notification_secret_ref"),1,128),flag(row.get("notification_configured")),parameters));
        }return List.copyOf(values);
    }
    static ObjectNode preferences(Preferences value){
        ObjectNode out=JSON.createObjectNode();out.put("revision",value.revision());out.set("follow_keywords",keywords(value.followKeywords()));
        out.set("blocked_keywords",keywords(value.blockedKeywords()));out.put("ai_paused",value.aiPaused());nullable(out,"default_package_id",value.defaultPackageId());out.set("operational_limits",rules(value.operationalLimits()));return out;
    }
    static Preferences preferences(String json){
        JsonNode in=CanonicalJson.parse(json);Set<String> fields=Set.of("revision","follow_keywords","blocked_keywords","ai_paused","default_package_id","operational_limits");
        if(!in.isObject()||in.size()!=fields.size())throw invalid();for(String field:fields)if(!in.has(field))throw invalid();
        return new Preferences(number(in.get("revision")),keywords(in.get("follow_keywords")),keywords(in.get("blocked_keywords")),flag(in.get("ai_paused")),optionalId(in.get("default_package_id")),rules(in.get("operational_limits")));
    }
    static ObjectNode patch(PreferencesWrite command,long operationalRevision){
        ObjectNode out=JSON.createObjectNode();out.put("mapping_version","1");out.set("follow_keywords",keywords(command.followKeywords()));out.set("blocked_keywords",keywords(command.blockedKeywords()));
        out.put("ai_paused",command.aiPaused());out.put("pause_new_jobs",command.aiPaused());nullable(out,"default_package_id",command.defaultPackageId());
        out.set("operational_limits",rules(command.operationalLimits()));out.put("operational_config_revision",operationalRevision);return out;
    }
    static String operational(List<OperationalRule> values){ObjectNode out=JSON.createObjectNode();out.put("schema_version","1");out.set("rules",rules(values));return out.toString();}
    static List<OperationalRule> operational(String json){JsonNode out=CanonicalJson.parse(json);if(!out.isObject()||out.size()!=2||!out.path("schema_version").isTextual()||!"1".equals(out.path("schema_version").asText()))throw invalid();return rules(out.get("rules"));}
    static List<String> keywords(JsonNode node){if(node==null||!node.isArray()||node.size()>100)throw invalid();List<String> out=new ArrayList<>();for(JsonNode value:node)out.add(text(value,1,128));return List.copyOf(out);}
    private static ArrayNode keywords(List<String> values){ArrayNode out=JSON.createArrayNode();values.forEach(out::add);return out;}
    static String optionalId(JsonNode node){if(node==null)throw invalid();if(node.isNull())return null;String value=text(node,1,64);if(!value.matches("[^\\x00-\\x1f\\x7f]+"))throw invalid();return value;}
    static boolean flag(JsonNode node){if(node==null||!node.isBoolean())throw invalid();return node.booleanValue();}
    static long number(JsonNode node){if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<0||node.longValue()>CanonicalJson.MAX_SAFE_INTEGER)throw invalid();return node.longValue();}
    static String text(JsonNode node,int min,int max){if(node==null||!node.isTextual())throw invalid();String value=node.asText();int length=value.codePointCount(0,value.length());if(length<min||length>max)throw invalid();return value;}
    private static String decimal(JsonNode node){String value=text(node,1,30);if(!value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]{1,8})?"))throw invalid();return value;}
    private static void nullable(ObjectNode node,String key,String value){if(value==null)node.putNull(key);else node.put(key,value);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid mapped settings");}
}
