package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.text.Normalizer;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Deterministic literal filtering of original full fields, independent of the model material prefix. */
public final class NewsPreferenceFilter {
    private NewsPreferenceFilter(){ }
    public static boolean blocked(String sourceId,SourceMemberEvidenceService.Fact fact,List<String> keywords) {
        try {
            NewsValueMaterial.from(sourceId,fact);
            if(keywords==null||keywords.size()>100||new HashSet<>(keywords).size()!=keywords.size())throw invalid();
            var words=new ArrayList<String>();
            for(var keyword:keywords) {
                if(keyword==null||keyword.isBlank()||keyword.codePointCount(0,keyword.length())>128)throw invalid();
                CanonicalJson.bytes(JsonMapper.shared().getNodeFactory().stringNode(keyword));String normalized=normalize(keyword.strip());if(normalized.isBlank())throw invalid();words.add(normalized);
            }
            var original=CanonicalJson.parse(fact.payloadJson());
            for(String field:List.of("title","source_summary","body_text")) {
                var value=original.get(field);if(value.isNull())continue;String text=normalize(value.asText());
                for(String word:words)if(text.contains(word))return true;
            }
            return false;
        }catch(RuntimeException rejected){throw invalid();}
    }
    private static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("original news preference filtering unavailable");}
}
