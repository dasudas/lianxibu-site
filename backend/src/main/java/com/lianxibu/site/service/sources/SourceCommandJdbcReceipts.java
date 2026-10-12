package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.sources.JdbcSourceCommandReceipts;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One trusted request binding; replay reads the original snapshot rather than current state. */
public final class SourceCommandJdbcReceipts implements SourceAdmissionService.ActionReceipts {
    private final JdbcSourceCommandReceipts store;
    private final OwnerSourceReadService sources;
    private final Actor actor;
    private final String key,hash;
    private final Clock clock;
    public SourceCommandJdbcReceipts(JdbcSourceCommandReceipts store,OwnerSourceReadService sources,Actor actor,String key,JsonNode command,Clock clock){
        if(actor==null||!"owner".equals(actor.role()))throw new IllegalArgumentException("owner required");WireValues.id(actor.userId(),false);
        if(key==null||!key.matches("[!-~]{1,128}")||command==null||!command.isObject())throw new IllegalArgumentException("source command required");
        this.store=java.util.Objects.requireNonNull(store);this.sources=java.util.Objects.requireNonNull(sources);this.actor=actor;this.key=key;this.hash=CanonicalJson.hash(CanonicalJson.Domain.ACTION,command);this.clock=java.util.Objects.requireNonNull(clock);
    }
    @Override public SourceAdmissionService.Decision committed(String actionKey){
        JsonNode receipt=read(actionKey);if(receipt==null)return null;
        var d=receipt.get("decision");WireValues.object(d,Set.of("source_id","revision","config_revision","activation_stage","action"),Set.of("source_id","revision","config_revision","activation_stage","action"));
        var decision=new SourceAdmissionService.Decision(WireValues.id(d.get("source_id"),true),WireValues.nonnegativeInteger(d.get("revision")),WireValues.nonnegativeInteger(d.get("config_revision")),WireValues.text(d.get("activation_stage")),WireValues.text(d.get("action")));
        var snapshot=snapshot(receipt);OwnerSourceReadService.validateProjection(snapshot,decision.sourceId());
        JsonNode s=receipt.get("source");if(s.get("revision").longValue()!=decision.revision()||s.get("config_revision").longValue()!=decision.configRevision()||!s.get("activation_stage").asText().equals(decision.activationStage()))throw conflict();
        return decision;
    }
    @Override public void record(String actionKey,SourceAdmissionService.Decision decision){
        sameKey(actionKey);
        var snapshot=sources.read(actor,decision.sourceId());var root=JsonMapper.shared().createObjectNode();var d=root.putObject("decision");
        d.put("source_id",decision.sourceId());d.put("revision",decision.revision());d.put("config_revision",decision.configRevision());d.put("activation_stage",decision.activationStage());d.put("action",decision.action());root.set("source",JsonMapper.shared().valueToTree(snapshot));
        store.save(actor.userId(),key,hash,new String(CanonicalJson.bytes(root),java.nio.charset.StandardCharsets.UTF_8),decision.revision(),clock.instant());
    }
    public Map<String,Object> originalSource(){var value=read(key);if(value==null)throw conflict();committed(key);return snapshot(value);}
    public String operationId(){return JdbcSourceCommandReceipts.id(actor.userId(),key);}
    private JsonNode read(String actionKey){sameKey(actionKey);var value=store.read(actor.userId(),key);if(value==null)return null;if(!hash.equals(value.hash()))throw conflict();var node=CanonicalJson.parse(value.json());WireValues.object(node,Set.of("decision","source"),Set.of("decision","source"));return node;}
    @SuppressWarnings("unchecked") private static Map<String,Object> snapshot(JsonNode receipt){return java.util.Collections.unmodifiableMap(JsonMapper.shared().convertValue(receipt.get("source"),Map.class));}
    private void sameKey(String value){if(!key.equals(value))throw conflict();}
    private static IllegalArgumentException conflict(){return new IllegalArgumentException("source_command_conflict");}
}
