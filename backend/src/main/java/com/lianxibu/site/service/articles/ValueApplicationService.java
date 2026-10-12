package com.lianxibu.site.service.articles;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.JdbcValueWrites;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Joins result application; root receipts own replay, all member failures roll back. */
public final class ValueApplicationService {
    public record Value(String itemId,long expectedRevision,String category,boolean highValue,boolean selected,String reason,String memberCategory,String entityId,Boolean valuable) {
        public Value(String itemId,long expectedRevision,String category,boolean highValue,boolean selected,String reason,String memberCategory,String entityId) {
            this(itemId,expectedRevision,category,highValue,selected,reason,memberCategory,entityId,null);
        }
        /** Isolated legacy decisions cannot change the original physical category. */
        public Value(String itemId,long expectedRevision,String category,boolean highValue,boolean selected,String reason) {
            this(itemId,expectedRevision,category,highValue,selected,reason,category,null,null);
        }
        @Override public String toString(){return "MemberValueDecision[redacted]";}
    }
    private final JdbcValueWrites store;
    public ValueApplicationService(JdbcValueWrites store){this.store=Objects.requireNonNull(store);}
    public void applyInTransaction(String rootId,String inputHash,String resultHash,List<Value> values) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("value result requires shared write transaction");
        WireValues.id(rootId,false);hash(inputHash);hash(resultHash);
        if(values==null||values.isEmpty()||values.size()>100)throw invalid();
        Set<String> seen=new HashSet<>();int selected=0;
        for(var value:values) {
            if(value==null)throw invalid();WireValues.id(value.itemId(),false);
            if(!seen.add(value.itemId())||value.expectedRevision()<0||value.expectedRevision()>=CanonicalJson.MAX_SAFE_INTEGER
                ||!Set.of("news_tech","agent","skill","workflow","model").contains(value.category())
                ||value.memberCategory()==null||!Set.of("news_tech","agent","skill","workflow","model").contains(value.memberCategory())
                ||value.entityId()==null&&!value.memberCategory().equals(value.category())
                ||value.valuable()!=null&&(value.entityId()==null||value.highValue()&&!value.valuable())
                ||value.reason()==null||value.reason().isBlank()||value.reason().codePointCount(0,value.reason().length())>2048||value.selected()&&!value.highValue())throw invalid();
            if(value.entityId()!=null)WireValues.id(value.entityId(),false);
            if(value.selected())selected++;
        }
        if(selected>5)throw invalid();
        var sorted=values.stream().sorted((a,b)->Arrays.compareUnsigned(a.itemId().getBytes(StandardCharsets.UTF_8),b.itemId().getBytes(StandardCharsets.UTF_8))).toList();
        Map<String,JdbcValueWrites.Member> locked=new HashMap<>();
        for(var value:sorted) {
            var row=store.lock(value.itemId());
            if(row==null||!row.id().equals(value.itemId())||row.revision()!=value.expectedRevision()||!value.memberCategory().equals(row.category())
                ||value.entityId()!=null&&!value.entityId().equals(row.entityId())
                ||row.entityId()==null||!row.valid()||!"accepted".equals(row.terminalState())||!"ready".equals(row.state())||"needs_review".equals(row.projectionDecision()))throw invalid();
            locked.put(row.id(),row);
        }
        for(var value:sorted) {
            var json=JsonMapper.shared().createObjectNode();json.put("schema_version",value.valuable()!=null?"member-value-v3":value.entityId()==null?"member-value-v1":"member-value-v2");json.put("root_id",rootId);json.put("input_hash",inputHash);json.put("result_hash",resultHash);
            json.put("expected_revision",value.expectedRevision());json.put("category",value.category());json.put("high_value",value.highValue());json.put("selected",value.selected());json.put("reason",value.reason());
            if(value.entityId()!=null){json.put("member_category",value.memberCategory());json.put("entity_id",value.entityId());}
            if(value.valuable()!=null)json.put("valuable",value.valuable());
            store.save(locked.get(value.itemId()),value.highValue(),new String(CanonicalJson.bytes(json),StandardCharsets.UTF_8));
        }
    }
    private static void hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("value member identity or version conflict");}
}
