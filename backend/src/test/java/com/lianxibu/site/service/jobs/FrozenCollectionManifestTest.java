package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.sources.CollectionHandoffService.Facts;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenCollectionManifestTest {
    static CollectionHandoffProofService.Proof proof(String run) {
        return new CollectionHandoffProofService.Proof("fictional-intent", "fictional-disposition-"+run,
                "a".repeat(64), new Facts(run,"fictional-source","daily",LocalDate.of(2026,1,1),1,
                "news_tech","production",1,"b".repeat(64),"c".repeat(64),"complete","succeeded",2,2,0));
    }
    @Test void stableOrderingPreservesEveryOriginalFactAndReturnsImmutableInventory() {
        var a=proof("fictional-a");var b=proof("fictional-b");
        var first=FrozenCollectionManifest.create(List.of(b,a));var second=FrozenCollectionManifest.create(List.of(a,b));
        assertArrayEquals(CanonicalJson.bytes(first),CanonicalJson.bytes(second));
        var read=FrozenCollectionManifest.read(first);assertEquals(List.of(a,b),read);
        assertThrows(UnsupportedOperationException.class,()->read.clear());
        assertEquals(2,first.path("handoffs").get(0).path("facts").path("accepted").asLong());
    }
    @Test void originalPayloadIdentityChangesInputHashAndExtraFieldsCannotHideInFacts() {
        var p=proof("fictional-a");var changed=new CollectionHandoffProofService.Proof(p.intentId(),p.dispositionId(),"d".repeat(64),p.facts());
        var first=(ObjectNode)FrozenCollectionManifest.create(List.of(p));
        assertNotEquals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,first),CanonicalJson.hash(CanonicalJson.Domain.INPUT,FrozenCollectionManifest.create(List.of(changed))));
        ((ObjectNode)first.path("handoffs").get(0).path("facts")).put("unknown",true);
        assertThrows(RuntimeException.class,()->FrozenCollectionManifest.read(first));
    }
    @Test void textualNumbersAndInvalidTerminalCountsDoNotBecomeValidFacts() {
        for(boolean text:List.of(true,false)) {
            var node=FrozenCollectionManifest.create(List.of(proof("fictional-a")));
            var facts=(ObjectNode)node.path("handoffs").get(0).path("facts");
            if(text)facts.put("accepted","2");else facts.put("excluded",1);
            assertThrows(RuntimeException.class,()->FrozenCollectionManifest.read(node));
        }
    }
    @Test void duplicatesLimitsMissingSchemaAndNoncanonicalOrderAreRejected() {
        var p=proof("fictional-a");
        assertThrows(RuntimeException.class,()->FrozenCollectionManifest.create(List.of(p,p)));
        assertThrows(RuntimeException.class,()->FrozenCollectionManifest.create(Collections.nCopies(129,p)));
        assertThrows(RuntimeException.class,()->FrozenCollectionManifest.read(null));
        var node=(ObjectNode)FrozenCollectionManifest.create(List.of(p));node.remove("schema_version");
        assertThrows(RuntimeException.class,()->FrozenCollectionManifest.read(node));
        var reversed=(ObjectNode)FrozenCollectionManifest.create(List.of(p,proof("fictional-b")));
        var items=reversed.putArray("handoffs");
        items.add(FrozenCollectionManifest.create(List.of(proof("fictional-b"))).path("handoffs").get(0));
        items.add(FrozenCollectionManifest.create(List.of(p)).path("handoffs").get(0));
        assertThrows(RuntimeException.class,()->FrozenCollectionManifest.read(reversed));
        assertEquals(List.of(),FrozenCollectionManifest.read(FrozenCollectionManifest.create(List.of())));
    }
}
