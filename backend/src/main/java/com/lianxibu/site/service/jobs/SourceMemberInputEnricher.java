package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Connects selected original evidence to locked source member facts in the preparation transaction. */
public final class SourceMemberInputEnricher implements GenerationFlowService.InputEnricher {
    private final SourceMemberEvidenceService evidence;
    public SourceMemberInputEnricher(SourceMemberEvidenceService evidence){this.evidence=Objects.requireNonNull(evidence);}
    @Override public JsonNode fields(String rootId,GenerationFlowService.Prepare command,Instant cutoff,JsonNode snapshot) {
        if(command==null||!(command.stage().dedup()||command.stage()==GenerationFlowService.Stage.NEWS_VALUE))throw invalid();
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        if(!context.referencesComplete()||context.stage()!=command.stage()||context.generation()!=command.generation()
            ||command.applicationContext()==null||!context.json().equals(command.applicationContext().json()))throw invalid();
        var input=context.input(rootId,"input-preparation",CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot),0);
        var selected=snapshot.get("members");if(selected==null||!selected.isArray()||selected.isEmpty()||selected.size()>100)throw invalid();
        Map<String,JsonNode> members=new HashMap<>();
        for(var member:selected){String id=WireValues.id(member.get("id"),false);if(members.putIfAbsent(id,member)!=null)throw invalid();}
        if(!members.keySet().equals(input.itemIds()))throw invalid();
        var extension=JsonMapper.shared().createObjectNode();var profile=extension.putObject(FrozenMemberBindings.FIELD);
        profile.put("schema_version","frozen-member-bindings-v1");var bindings=profile.putArray("members");
        for(var fact:evidence.read(input.itemIds(),cutoff)) {
            var member=members.get(fact.id());var body=member.get("body");
            if(body==null||!Arrays.equals(CanonicalJson.bytes(body),CanonicalJson.bytes(CanonicalJson.parse(fact.payloadJson())))
                ||!CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body).equals(WireValues.text(member.get("body_hash"))))throw invalid();
            var binding=bindings.addObject();binding.put("item_id",fact.id());binding.put("expected_revision",fact.revision());
            binding.put("category",fact.category());binding.put("identity_key",fact.identityKey());binding.put("title",fact.title());
            if(fact.summary()==null)binding.putNull("summary");else binding.put("summary",fact.summary());
            binding.put("new_entity_id",FrozenMemberBindings.entityId(rootId,fact.id(),fact.category(),fact.identityKey()));
        }
        return extension;
    }
    private static GenerationFlowService.InputRejected invalid(){return new GenerationFlowService.InputRejected();}
}
