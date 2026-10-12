package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;

class NewsPreferenceFilterTest {
    static final String SOURCE="fictional-source";
    static SourceMemberEvidenceService.Fact fact(String title,String summary,String body) {
        var node=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);
        node.put("title",title);if(summary==null)node.putNull("source_summary");else node.put("source_summary",summary);if(body==null)node.putNull("body_text");else node.put("body_text",body);
        var key=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("namespace","article_url");key.set("key",node.get("source_url"));
        return new SourceMemberEvidenceService.Fact("fictional-member",1,"news_tech",canonical(key),title,summary,canonical(node));
    }
    static String canonical(tools.jackson.databind.JsonNode node){return new String(CanonicalJson.bytes(node),java.nio.charset.StandardCharsets.UTF_8);}
    @Test void checksTheFullOriginalBodyBeyondTheModelPrefixEvenWhenASummaryExists() {
        var item=fact("Fictional title","Fictional summary","x".repeat(801)+"BLOCKED fictional tail");
        assertTrue(NewsPreferenceFilter.blocked(SOURCE,item,List.of("blocked")));assertEquals("summary",NewsValueMaterial.from(SOURCE,item).inputBasis());
        item=fact("Fictional title",null,"x".repeat(801)+"blocked fictional tail");assertTrue(NewsPreferenceFilter.blocked(SOURCE,item,List.of("BLOCKED")));
        assertFalse(NewsValueMaterial.from(SOURCE,item).text().contains("blocked"));
    }
    @Test void literalUnicodeMatchingChecksEachOriginalFieldWithoutInventingCrossFieldText() {
        assertTrue(NewsPreferenceFilter.blocked(SOURCE,fact("ＦＯＯ Fictional",null,null),List.of("foo")));
        assertTrue(NewsPreferenceFilter.blocked(SOURCE,fact("Fictional title","虚构屏蔽说明",null),List.of("屏蔽")));
        assertTrue(NewsPreferenceFilter.blocked(SOURCE,fact("Fictional title",null,"Fictional [.*] text"),List.of("[.*]")));
        assertFalse(NewsPreferenceFilter.blocked(SOURCE,fact("Fictional foo","bar fictional",null),List.of("foobar")));
        assertFalse(NewsPreferenceFilter.blocked(SOURCE,fact("Fictional anything",null,null),List.of("[.*]")));
    }
    @Test void emptyPreferencesAndMissingMaterialDoNotInventPositiveFollowRequirements() {
        var item=fact("Fictional title",null,null);assertFalse(NewsPreferenceFilter.blocked(SOURCE,item,List.of()));assertFalse(NewsPreferenceFilter.blocked(SOURCE,item,List.of("unrelated")));
        assertTrue(NewsPreferenceFilter.blocked(SOURCE,item,List.of("  TITLE  ")));assertTrue(NewsPreferenceFilter.blocked(SOURCE,item,List.of("TITLE","title")));
    }
    @Test void badKeywordsAndOriginalFactTamperingAreRejected() {
        var item=fact("Fictional title","Fictional summary",null);
        for(var words:List.of(List.of(""),List.of("  "),List.of("x".repeat(129)),List.of("x","x"),Collections.nCopies(101,"x")))assertThrows(RuntimeException.class,()->NewsPreferenceFilter.blocked(SOURCE,item,words));
        assertThrows(RuntimeException.class,()->NewsPreferenceFilter.blocked(SOURCE,item,null));assertThrows(RuntimeException.class,()->NewsPreferenceFilter.blocked(SOURCE,item,Arrays.asList((String)null)));
        var bad=new SourceMemberEvidenceService.Fact(item.id(),item.revision(),item.category(),item.identityKey(),"Fictional replaced",item.summary(),item.payloadJson());assertThrows(RuntimeException.class,()->NewsPreferenceFilter.blocked(SOURCE,bad,List.of()));
        assertThrows(RuntimeException.class,()->NewsPreferenceFilter.blocked(SOURCE,item,List.of("\uD800")));
    }
}
