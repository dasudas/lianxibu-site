package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Original collection intent addresses. An address proves neither existence nor permission. */
public final class CollectionIntentIdentity {
    private CollectionIntentIdentity(){ }
    public record Address(String logicalKey,String id,String taskType,long generation,LocalDate day) {
        @Override public String toString(){return "CollectionIntentAddress[redacted]";}
    }
    public static Address create(String sourceId,String kind,LocalDate day,long generation,String category) {
        WireValues.id(sourceId,true);Objects.requireNonNull(day);
        if(kind==null||!Set.of("daily","historical_backfill","production_qualification","trial","correction").contains(kind)
                ||category==null||!Set.of("news_tech","agent","skill","workflow","model").contains(category)
                ||generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER)throw new IllegalArgumentException("collection identity scope invalid");
        String stage=("news_tech".equals(category)?"news":category)+"_dedup";
        var natural=JsonMapper.shared().createObjectNode().put("source_id",sourceId).put("run_kind",kind)
                .put("business_date",day.toString()).put("stage",stage);
        String logical="collection:"+CanonicalJson.hash(CanonicalJson.Domain.ACTION,natural);
        return new Address(logical,JdbcFrozenInputs.stableId("intent",logical+":"+generation),stage,generation,day);
    }
}
