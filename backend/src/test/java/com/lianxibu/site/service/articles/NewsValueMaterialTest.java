package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class NewsValueMaterialTest {
    SourceMemberEvidenceService.Fact fact(String summary,String body){
        var value=(ObjectNode)CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);if(summary==null)value.putNull("source_summary");else value.put("source_summary",summary);if(body==null)value.putNull("body_text");else value.put("body_text",body);
        return new SourceMemberEvidenceService.Fact("fictional-item",7,"news_tech","{\"key\":\"https://example.invalid/story\",\"namespace\":\"article_url\"}","Fictional source title",summary,new String(CanonicalJson.bytes(value),StandardCharsets.UTF_8));
    }
    NewsValueMaterial material(String summary,String body){return NewsValueMaterial.from("fictional-source",fact(summary,body));}
    @Test void fullLongSummaryTakesPrecedenceAndNeverConcatenatesBody(){
        String summary="Fictional summary "+"😀".repeat(1800);var value=material("  "+summary+"  ","Fictional unused body");
        assertEquals("summary",value.inputBasis());assertEquals(summary,value.text());assertTrue(value.text().codePointCount(0,value.text().length())>800);assertFalse(value.json().contains("unused body"));assertEquals(7,value.expectedRevision());assertEquals("fictional-item",value.itemId());
        assertEquals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(fact("  "+summary+"  ","Fictional unused body").payloadJson())),value.originalBodyHash());
    }
    @Test void bodyPrefixUsesExactly800CodePointsWithoutSplittingSupplementaryCharacters(){
        var value=material(" \t\n","  "+"😀".repeat(800)+"Fictional excluded tail  ");assertEquals("body_prefix",value.inputBasis());assertEquals("😀".repeat(800),value.text());assertEquals(1600,value.text().length());
        assertEquals("Fictional short body",material(null,"  Fictional short body  ").text());assertEquals("😀".repeat(799)+"x",material(null,"😀".repeat(799)+"xy").text());
        assertEquals("x".repeat(799)+" ",material(null,"x".repeat(799)+" y").text());
    }
    @Test void missingSummaryAndBodyAreExplicitAndNeverUseTitleAsReplacement(){
        for(String summary:new String[]{null,""," \n"})for(String body:new String[]{null,""," \t"}){var value=material(summary,body);assertEquals("missing",value.inputBasis());assertNull(value.text());assertFalse(value.json().contains("Fictional source title"));assertEquals(value,NewsValueMaterial.read(CanonicalJson.parse(value.json())));}
    }
    @Test void identityTitleSummaryCategoryRevisionAndNoncanonicalEvidenceReject(){
        var f=fact("Fictional summary","Fictional body");
        for(var bad:java.util.List.of(
                new SourceMemberEvidenceService.Fact(f.id(),f.revision(),"agent",f.identityKey(),f.title(),f.summary(),f.payloadJson()),
                new SourceMemberEvidenceService.Fact(f.id(),f.revision(),f.category(),"{\"key\":\"https://example.invalid/other\",\"namespace\":\"article_url\"}",f.title(),f.summary(),f.payloadJson()),
                new SourceMemberEvidenceService.Fact(f.id(),f.revision(),f.category(),f.identityKey(),"Fictional altered title",f.summary(),f.payloadJson()),
                new SourceMemberEvidenceService.Fact(f.id(),f.revision(),f.category(),f.identityKey(),f.title(),null,f.payloadJson()),
                new SourceMemberEvidenceService.Fact(f.id(),CanonicalJson.MAX_SAFE_INTEGER,f.category(),f.identityKey(),f.title(),f.summary(),f.payloadJson()),
                new SourceMemberEvidenceService.Fact(f.id(),f.revision(),f.category(),f.identityKey(),f.title(),f.summary(),f.payloadJson()+" ")))
            assertThrows(RuntimeException.class,()->NewsValueMaterial.from("fictional-source",bad));
        assertThrows(RuntimeException.class,()->material("😀".repeat(16385),null));
    }
    @Test void closedCodecValidatesBasisNullTypesLimitsAndRedactsToString(){
        var original=material("Fictional retained material",null);assertEquals(original,NewsValueMaterial.read(CanonicalJson.parse(original.json())));assertFalse(original.toString().contains("retained"));
        for(String mutation:java.util.List.of("extra","version","unsafe","negative","fraction","hash","basis","null","blank","prefix","summary")){
            var node=(ObjectNode)CanonicalJson.parse(original.json());switch(mutation){case "extra"->node.put("unknown",true);case "version"->node.put("schema_version","2");case "unsafe"->node.put("expected_revision",CanonicalJson.MAX_SAFE_INTEGER);case "negative"->node.put("expected_revision",-1);case "fraction"->node.put("expected_revision","1.1");case "hash"->node.put("original_body_hash","A".repeat(64));case "basis"->node.put("input_basis","guessed");case "null"->node.putNull("text");case "blank"->node.put("text"," ");case "prefix"->{node.put("input_basis","body_prefix");node.put("text","😀".repeat(801));}case "summary"->node.put("text","x".repeat(16385));}
            assertThrows(RuntimeException.class,()->NewsValueMaterial.read(node));
        }
    }
}
