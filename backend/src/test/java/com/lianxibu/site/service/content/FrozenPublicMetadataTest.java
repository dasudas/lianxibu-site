package com.lianxibu.site.service.content;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.FrozenApplicationContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenPublicMetadataTest {
    private static final String TARGET="{\"kind\":\"article_revision\",\"id\":\"fictional-revision\"}";
    private static final String PROFILE="""
        {"name":"Fictional model","provider":null,"tags":["fictional"],"description":null,
         "evidence_time":null,"metrics":[{"benchmark":"Fictional benchmark","benchmark_version":"v1","task":"fictional task",
         "score":"-0.25000000","unit":"points","evaluated_at":null,"higher_is_better":true,"evaluation_scope":"entity","provenance":"third_party"}],
         "stars":null,"stars_growth":0}
        """;
    static FrozenApplicationContext context(boolean report,String target) {
        String json="""
            {"schema_version":"frozen-application-context-v1","stage":"STAGE","generation":1,
             "target_content":{"kind":"KIND","id":"fictional-content","base_revision":null},"target_entity_id":ENTITY,
             "period_start":START,"period_end":END,"item_ids":[],"candidates_by_item":[],"eligible_high_value_item_ids":[],
             "allowed_citation_ids":["fictional-citation"],"version_eligible_item_ids":[],"reference_targets":[TARGET],"references_complete":true}
            """;
        return FrozenApplicationContext.fromJson(CanonicalJson.parse(json.replace("STAGE",report?"daily_report":"model_analysis")
            .replace("KIND",report?"report":"model").replace("ENTITY",report?"null":"\"fictional-entity\"")
            .replace("START",report?"\"2025-12-31T16:00:00.000000Z\"":"null").replace("END",report?"\"2026-01-01T16:00:00.000000Z\"":"null").replace("TARGET",target)));
    }
    static ObjectNode metadata(boolean report,String target) {
        return (ObjectNode)CanonicalJson.parse("{\"schema_version\":\"frozen-public-metadata-v1\",\"citations\":[{\"id\":\"fictional-citation\",\"target\":"+target+",\"title\":\"Fictional source\",\"source_url\":\"https://example.invalid/source\"}],\"interpretation\":"+(report?"null":PROFILE)+"}");
    }
    @Test void reportAndProfileAreClosedImmutablePublicValues() {
        var input=metadata(false,TARGET);var result=FrozenPublicMetadata.read(input,context(false,TARGET));
        assertEquals("Fictional model",result.interpretation().name());assertEquals("-0.25000000",result.interpretation().metrics().getFirst().score());
        assertEquals("article_revision",result.citations().getFirst().kind());assertNull(result.citations().getFirst().revision());
        input.putNull("interpretation");assertNotNull(result.interpretation());
        assertThrows(UnsupportedOperationException.class,()->result.citations().clear());
        assertNull(FrozenPublicMetadata.read(metadata(true,TARGET),context(true,TARGET)).interpretation());
    }
    @Test void exactContentVersionIsPreserved() {
        String target="{\"kind\":\"content_version\",\"content_kind\":\"report\",\"content_id\":\"fictional-report\",\"revision\":3,\"body_hash\":\""+"a".repeat(64)+"\",\"input_job_id\":\"fictional-owner\"}";
        var citation=FrozenPublicMetadata.read(metadata(true,target),context(true,target)).citations().getFirst();
        assertEquals(3,citation.revision());assertEquals("a".repeat(64),citation.bodyHash());
        assertThrows(IllegalArgumentException.class,()->FrozenPublicMetadata.read(metadata(true,target),context(true,target.replace("\"revision\":3","\"revision\":4"))));
    }
    @Test void missingDuplicateAndUnknownReferencesAreRejected() {
        var valid=metadata(true,TARGET);
        for(String bad:new String[]{valid.toString().replace("fictional-citation","other"),valid.toString().replace("fictional-revision","other"),valid.toString().replace("\"title\":","\"private_payload\":1,\"title\":"),valid.toString().replace("frozen-public-metadata-v1","v2")})
            assertThrows(IllegalArgumentException.class,()->FrozenPublicMetadata.read(CanonicalJson.parse(bad),context(true,TARGET)));
        var duplicate=metadata(true,TARGET);((tools.jackson.databind.node.ArrayNode)duplicate.get("citations")).add(duplicate.get("citations").get(0));
        assertThrows(IllegalArgumentException.class,()->FrozenPublicMetadata.read(duplicate,context(true,TARGET)));
        valid.remove("citations");assertThrows(IllegalArgumentException.class,()->FrozenPublicMetadata.read(valid,context(true,TARGET)));
    }
    @Test void unsafeUrlsAndProfileBoundsAreRejected() {
        for(String url:new String[]{"http://example.invalid/x","https://user:secret@example.invalid/x","https://example.invalid/x#secret","https://example.invalid/"+"a".repeat(2000)}) {
            var n=metadata(true,TARGET);((ObjectNode)n.get("citations").get(0)).put("source_url",url);
            assertThrows(IllegalArgumentException.class,()->FrozenPublicMetadata.read(n,context(true,TARGET)));
        }
        for(String bad:new String[]{PROFILE.replace("\"stars_growth\":0","\"stars_growth\":-1"),PROFILE.replace("\"points\"","\"unknown\""),PROFILE.replace("-0.25000000","1e4"),PROFILE.replace("[\"fictional\"]","[\"fictional\",\"fictional\"]"),PROFILE.replace("\"provider\":null","\"provider\":\""+"a".repeat(257)+"\"")}) {
            assertThrows(IllegalArgumentException.class,()->{
                var n=metadata(false,TARGET);n.set("interpretation",CanonicalJson.parse(bad));
                FrozenPublicMetadata.read(n,context(false,TARGET));
            });
        }
        assertThrows(IllegalArgumentException.class,()->FrozenPublicMetadata.read(metadata(false,TARGET),context(true,TARGET)));
        assertThrows(IllegalArgumentException.class,()->FrozenPublicMetadata.read(metadata(true,TARGET),context(false,TARGET)));
    }
}
