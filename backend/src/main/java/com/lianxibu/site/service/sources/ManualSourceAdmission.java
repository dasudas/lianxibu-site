package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.sources.JdbcManualSourceAdmissions;
import com.lianxibu.site.repository.sources.JdbcManualSourceAdmissions.Receipt;
import com.lianxibu.site.repository.sources.JdbcSourceAdmission;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** First-install host-reviewed path, independent from trial promotion. */
public final class ManualSourceAdmission {
    private static final Set<String> CHECKS=Set.of("tls_network","root_format","required_fields","stable_ids","time_fields","usage_scope","adapter_roundtrip");
    public static final List<String> EVIDENCE=List.of("usage_basis","field_mapping","access_policy","probe_evidence");
    public static final class Manifest {
        private final JsonNode root;
        private final String canonical;
        private Manifest(JsonNode root){this.root=root;this.canonical=new String(CanonicalJson.bytes(root),StandardCharsets.UTF_8);}
        public static Manifest parse(byte[] bytes){
            if(bytes==null||bytes.length>65536)throw new IllegalArgumentException("invalid manifest");
            var fields=Set.of("schema_version","action_key","source_id","reviewer_id","expected_revision","config_version",
                    "config_sha256","legacy_config_sha256","parser_version","reviewed_at","expires_at","valid_samples",
                    "usage_basis","field_mapping","access_policy","probe_evidence","checks");
            var m=new Manifest(WireValues.object(CanonicalJson.parse(bytes),fields,fields));
            if(!"manual-source-admission-v1".equals(m.text("schema_version")))throw denied();
            bounded(m.text("action_key"),128);WireValues.id(m.text("source_id"),true);WireValues.id(m.text("reviewer_id"),false);
            bounded(m.text("parser_version"),64);
            for(String f:List.of("expected_revision","config_version","valid_samples"))if(m.number(f)<1)throw denied();
            for(String f:List.of("config_sha256","legacy_config_sha256"))hash(m.text(f));
            Instant.parse(m.text("reviewed_at"));Instant.parse(m.text("expires_at"));
            WireValues.object(m.root.get("checks"),CHECKS,CHECKS);
            for(String f:CHECKS)if(!m.root.get("checks").get(f).isBoolean())throw denied();
            for(String f:EVIDENCE){var e=WireValues.object(m.root.get(f),Set.of("ref","sha256"),Set.of("ref","sha256"));
                bounded(WireValues.text(e.get("ref")),512);hash(WireValues.text(e.get("sha256")));}
            return m;
        }
        public String text(String key){return WireValues.text(root.get(key));}
        public long number(String key){long n=WireValues.nonnegativeInteger(root.get(key));if(n>9007199254740991L)throw denied();return n;}
        public String evidenceHash(String key){if(!EVIDENCE.contains(key))throw denied();return WireValues.text(root.get(key).get("sha256"));}
        public String canonical(){return canonical;}
        @Override public String toString(){return "ManualAdmissionManifest[redacted]";}
    }
    private final JdbcManualSourceAdmissions receipts;
    private final JdbcSourceAdmission sources;
    private final SharedWriteExecutor writes;
    private final Clock clock;
    public ManualSourceAdmission(JdbcManualSourceAdmissions receipts,JdbcSourceAdmission sources,SharedWriteExecutor writes,Clock clock){
        this.receipts=receipts;this.sources=sources;this.writes=writes;this.clock=clock;
    }
    public Receipt apply(Actor actor,String operation,Manifest m){
        if(actor==null||!"owner".equals(actor.role())||m==null||!m.text("reviewer_id").equals(actor.userId())
                ||!Set.of("approve","revoke").contains(operation)||!receipts.actorExists(actor.userId()))throw denied();
        byte[] key=digest(m.text("action_key")),hash=digest(operation+"\n"+m.canonical());
        var context=new ActionContext(m.text("action_key"),"manual-admission",HexFormat.of().formatHex(hash));
        return writes.execute(context,ignored->receipts.find(actor.userId(),key,hash),ignored->{
            var row=sources.locked(m.text("source_id"));
            if(row==null||row.revision()!=m.number("expected_revision"))throw denied();
            Instant now=clock.instant();
            if("approve".equals(operation)){
                Instant reviewed=Instant.parse(m.text("reviewed_at")),expires=Instant.parse(m.text("expires_at"));
                if(!"disabled".equals(row.activationStage())||row.draftConfigJson()==null||row.legacyConfigJson()==null
                        ||row.configRevision()!=m.number("config_version")
                        ||!SourceIdentityMappingService.sha256(row.draftConfigJson()).equals(m.text("config_sha256"))
                        ||!SourceIdentityMappingService.sha256(row.legacyConfigJson()).equals(m.text("legacy_config_sha256"))
                        ||reviewed.isAfter(now)||!expires.isAfter(now)||!expires.isAfter(reviewed))throw denied();
                JsonNode config=CanonicalJson.parse(row.draftConfigJson());
                if(!WireValues.text(config.get("parser_version")).equals(m.text("parser_version"))
                        ||expires.isAfter(Instant.parse(WireValues.text(config.get("valid_until")))))throw denied();
                for(String check:CHECKS)if(!m.root.get("checks").get(check).booleanValue())throw denied();
                int minimum="news_tech".equals(row.category())?3:1;
                if(row.category()==null||m.number("valid_samples")<minimum||!sources.setManualProduction(row,expires,now))throw denied();
            }else if(!sources.disable(row,now))throw denied();
            var updated=sources.locked(row.id());
            var result=new Receipt(updated.id(),updated.revision(),updated.activationStage());
            receipts.save(actor.userId(),m.text("action_key"),key,hash,operation,m.canonical(),result,now);
            return result;
        });
    }
    private static void hash(String value){if(!value.matches("[0-9a-f]{64}"))throw denied();}
    private static void bounded(String value,int max){if(value.isBlank()||value.length()>max||value.codePoints().anyMatch(Character::isISOControl))throw denied();}
    private static byte[] digest(String value){return HexFormat.of().parseHex(SourceIdentityMappingService.sha256(value));}
    private static IllegalArgumentException denied(){return new IllegalArgumentException("manual source admission denied");}
}
