package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;

class SourceValueNativeMissingOrderTest {
    @Test void TypedOrderStaysUnchangedWhileNativeStringsMatchPermanentPreparationOrder(){
        var f=new NewsValuePartialInputProfileTest();var x=f.fixture();String raw=SourceValueStageContext.create(x.partial(),f.f.day,f.f.cutoff,true);var context=CanonicalJson.parse(raw);assertEquals("source_fragment",context.get("missing").get(0).get("kind").asText());assertEquals("source_run",context.get("missing").get(1).get("kind").asText());var nativeMissing=SourceValueStageContext.partialSnapshotMissing(raw,f.f.day,f.f.cutoff);var sorted=new ArrayList<>(nativeMissing);sorted.sort(String::compareTo);assertEquals(sorted,nativeMissing);assertEquals("collection:"+x.original().universe().runId(),nativeMissing.getFirst());assertEquals(raw,SourceValueStageContext.fromPartialSnapshot(f.successful(x,true)));assertEquals(raw,SourceValueStageContext.readPartial(raw,f.f.day,f.f.cutoff));assertThrows(UnsupportedOperationException.class,nativeMissing::clear);
    }
    @Test void UnsortedNativeMappingCannotBeArchivedAsAValidPartialInput(){
        var f=new NewsValuePartialInputProfileTest();var x=f.fixture();var input=f.successful(x,true);var missing=(ArrayNode)input.get("missing");var strings=missing.valueStream().map(n->n.asText()).sorted(Comparator.reverseOrder()).toList();missing.removeAll();strings.forEach(missing::add);assertThrows(RuntimeException.class,()->SourceValueStageContext.fromPartialSnapshot(input));assertThrows(RuntimeException.class,()->NewsValueInputProfile.complete(input));
    }
}
