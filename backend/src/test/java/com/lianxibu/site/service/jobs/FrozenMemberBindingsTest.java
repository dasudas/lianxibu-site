package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class FrozenMemberBindingsTest {
    static final String ROOT="fictional-root";
    static ObjectNode snapshot() {
        var root=JsonMapper.shared().createObjectNode();root.set(FrozenApplicationContext.FIELD,CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.NEWS_DEDUP)));
        var profile=root.putObject("member_bindings_v1");profile.put("schema_version","frozen-member-bindings-v1");
        var member=profile.putArray("members").addObject();member.put("item_id","fictional-item");member.put("expected_revision",2);member.put("category","news_tech");member.put("identity_key","fictional-canonical-identity");member.put("title","Fictional original title");member.putNull("summary");
        member.put("new_entity_id",FrozenMemberBindings.entityId(ROOT,"fictional-item","news_tech","fictional-canonical-identity"));return root;
    }
    static ResultApplicationService.Input input(ObjectNode root) {return FrozenApplicationContext.fromJson(root.get(FrozenApplicationContext.FIELD)).input(ROOT,"fictional-attempt",CanonicalJson.hash(CanonicalJson.Domain.INPUT,root),1);}
    @Test void exactMembersAndDeterministicIdentityAreImmutable() {
        var root=snapshot();var result=FrozenMemberBindings.read(root,input(root));var member=result.get("fictional-item");assertEquals(2,member.expectedRevision());assertEquals("fictional-canonical-identity",member.identityKey());
        ((ObjectNode)root.get("member_bindings_v1").get("members").get(0)).put("title","Fictional later title");assertEquals("Fictional original title",member.title());
        assertThrows(UnsupportedOperationException.class,()->result.clear());
        assertNotEquals(member.newEntityId(),FrozenMemberBindings.entityId("fictional-other-root","fictional-item","news_tech","fictional-canonical-identity"));
    }
    @Test void changedInputAndRehashedInvalidBindingsAreRejected() {
        var root=snapshot();var original=input(root);((ObjectNode)root.get("member_bindings_v1").get("members").get(0)).put("expected_revision",3);
        assertThrows(RuntimeException.class,()->FrozenMemberBindings.read(root,original));
        for(String field:new String[]{"category","item_id","new_entity_id"}) {
            var bad=snapshot();((ObjectNode)bad.get("member_bindings_v1").get("members").get(0)).put(field,"fictional-wrong");
            assertThrows(RuntimeException.class,()->FrozenMemberBindings.read(bad,input(bad)));
        }
        var duplicate=snapshot();var list=(tools.jackson.databind.node.ArrayNode)duplicate.get("member_bindings_v1").get("members");list.add(list.get(0));
        assertThrows(RuntimeException.class,()->FrozenMemberBindings.read(duplicate,input(duplicate)));
        var extra=snapshot();((ObjectNode)extra.get("member_bindings_v1").get("members").get(0)).put("private_payload","fictional");
        assertThrows(RuntimeException.class,()->FrozenMemberBindings.read(extra,input(extra)));
        var absent=snapshot();((ObjectNode)absent.get("member_bindings_v1")).putArray("members");
        assertThrows(RuntimeException.class,()->FrozenMemberBindings.read(absent,input(absent)));
    }
}
