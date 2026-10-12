package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.sources.MemberUniverse.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.sources.JdbcSourceRunUniverse;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Proves the actual complete manifest membership, without treating it as selected analysis input. */
public final class SourceRunUniverseService implements Reader {
    private final JdbcSourceRunUniverse store;private final CollectionHandoffService handoffs;
    public SourceRunUniverseService(JdbcSourceRunUniverse store,CollectionHandoffService handoffs){this.store=Objects.requireNonNull(store);this.handoffs=Objects.requireNonNull(handoffs);}
    @Override public Universe read(String runId) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw invalid();
        try{return verified(WireValues.id(runId,false));}catch(RuntimeException unavailable){throw invalid();}
    }
    private Universe verified(String runId) {
        var facts=handoffs.terminalFacts(runId).facts();if(facts.expected()>MemberUniverse.MAX_MEMBERS)throw invalid();
        var metadata=store.metadata(runId);if(metadata==null||!runId.equals(metadata.id())||metadata.parts()==null||metadata.parts()<0||metadata.parts()>MemberUniverse.MAX_PARTS)throw invalid();
        var result=object(CanonicalJson.parse(metadata.result()),Set.of("run_id","manifest_hash","part_count","expected_count","fetch_outcome","coverage"));
        if(!runId.equals(text(result,"run_id"))||!facts.manifestHash().equals(text(result,"manifest_hash"))||metadata.parts()!=number(result,"part_count")
                ||facts.expected()!=number(result,"expected_count")||!facts.fetchState().equals(text(result,"fetch_outcome"))||!result.get("coverage").isObject())throw invalid();
        var parts=new TreeMap<Long,RunManifestService.Part>();var original=new HashMap<String,RunManifestService.Member>();
        store.receipts(runId,receipt->{
            if(!runId.equals(receipt.runId())||receipt.businessHash()==null||receipt.businessHash().length!=32)throw invalid();
            var p=object(CanonicalJson.parse(receipt.json()),Set.of("run_id","part_no","part_hash","members"));long no=number(p,"part_no");
            if(!runId.equals(text(p,"run_id"))||!Objects.equals(receipt.scope(),"manifest:"+runId+":"+no)||no>=metadata.parts()||!p.get("members").isArray()||p.get("members").size()>100)throw invalid();
            var action=JsonMapper.shared().createObjectNode().put("operation","manifest");action.set("business",p);
            if(!MessageDigest.isEqual(receipt.businessHash(),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.CONTROL_BUSINESS,action))))throw invalid();
            var unhashed=JsonMapper.shared().createObjectNode().put("run_id",runId).put("part_no",no);unhashed.set("members",p.get("members"));if(CanonicalJson.bytes(unhashed).length>128*1024)throw invalid();
            var list=new ArrayList<RunManifestService.Member>();String previous=null;
            for(var value:p.get("members")) {
                var m=object(value,Set.of("member_id","source_item_id","observation_id","payload_hash","replaces_observation_id"));
                String member=WireValues.id(m.get("member_id"),false),payload=text(m,"payload_hash");
                if(!payload.matches("[0-9a-f]{64}")||previous!=null&&member.compareTo(previous)<=0)throw invalid();
                var originalMember=new RunManifestService.Member(member,nullable(m,"source_item_id"),WireValues.id(m.get("observation_id"),false),payload,m.get("replaces_observation_id").isNull()?null:WireValues.id(m.get("replaces_observation_id"),false));
                if(original.putIfAbsent(member,originalMember)!=null||original.size()>MemberUniverse.MAX_MEMBERS)throw invalid();list.add(originalMember);previous=member;
            }
            String hash=text(p,"part_hash");if(!RunManifestService.partHash(runId,no,list).equals(hash)||parts.putIfAbsent(no,new RunManifestService.Part(runId,no,hash,List.copyOf(list)))!=null)throw invalid();
        });
        if(parts.size()!=metadata.parts()||original.size()!=facts.expected()||!facts.manifestHash().equals(RunManifestService.manifestHash(runId,facts.expected(),List.copyOf(parts.values()))))throw invalid();
        var members=new ArrayList<Member>();
        for(var row:store.members(runId)) {
            if(!runId.equals(row.runId())||!facts.sourceId().equals(row.sourceId())||row.payloadHash()==null||row.payloadHash().length!=32)throw invalid();
            var expected=original.remove(row.memberId());
            if(expected==null||!expected.equals(new RunManifestService.Member(row.memberId(),row.sourceItemId(),row.observationId(),HexFormat.of().formatHex(row.payloadHash()),row.replacesObservationId())))throw invalid();
            var part=parts.get(row.partNo());if(part==null||!part.members().contains(expected))throw invalid();
            members.add(new Member(row.id(),row.memberId(),row.sourceItemId(),row.observationId(),expected.payloadHash(),row.replacesObservationId(),row.partNo(),row.terminal()));
        }
        if(!original.isEmpty())throw invalid();members.sort(Comparator.comparingLong(Member::partNo).thenComparing(Member::memberId));
        return new Universe(runId,facts.sourceId(),facts.manifestHash(),parts.values().stream().map(p->new Part(p.partNo(),p.partHash(),p.members().size())).toList(),members,facts.accepted(),facts.excluded());
    }
    private static JsonNode object(JsonNode value,Set<String> fields){return WireValues.object(value,fields,fields);}
    private static String text(JsonNode value,String name){return WireValues.text(value.get(name));}
    private static long number(JsonNode value,String name){return WireValues.nonnegativeInteger(value.get(name));}
    private static String nullable(JsonNode value,String name){return value.get(name).isNull()?null:text(value,name);}
    private static IllegalStateException invalid(){return new IllegalStateException("complete original source manifest unavailable");}
}
