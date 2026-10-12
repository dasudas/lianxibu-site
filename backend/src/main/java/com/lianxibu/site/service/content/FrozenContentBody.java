package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.service.content.ContentService.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Lossless decoding of the exact proven body, without current-row fallbacks. */
public final class FrozenContentBody {
    private FrozenContentBody() { }
    public static ContentBody read(String kind,JsonNode body) {
        boolean report="report".equals(kind);
        if(!report&&!Set.of("agent","model","skill","workflow").contains(kind))throw invalid();
        if(report)closed(body,"title","sections","citation_ids","type","period_start","period_end","timezone","learning_task");
        else closed(body,"title","sections","citation_ids","category","entity_id");
        String title=text(body.get("title"),1,512);List<String> citations=ids(body.get("citation_ids"));
        var sectionsNode=body.get("sections");if(!sectionsNode.isArray()||sectionsNode.size()>100)throw invalid();
        List<Section> sections=new ArrayList<>();
        for(var section:sectionsNode) {
            closed(section,"heading","text","citation_ids");var refs=ids(section.get("citation_ids"));
            sections.add(new Section(text(section.get("heading"),1,512),text(section.get("text"),0,200000),refs));
        }
        ContentBody result;
        if(report) {
            String type=text(body.get("type"),1,32);if(!Set.of("daily","weekly","monthly").contains(type))throw invalid();
            var start=WireValues.utcMicroseconds(body.get("period_start"));var end=WireValues.utcMicroseconds(body.get("period_end"));
            if(!end.isAfter(start)||!"Asia/Shanghai".equals(WireValues.text(body.get("timezone"))))throw invalid();
            LearningTask task=null;var node=body.get("learning_task");
            if(!node.isNull()) {
                closed(node,"package_id","object_kind","object_id","title","reason");String objectKind=WireValues.text(node.get("object_kind"));
                if(!Set.of("node","question","project").contains(objectKind))throw invalid();
                task=new LearningTask(WireValues.id(node.get("package_id"),false),objectKind,WireValues.id(node.get("object_id"),false),text(node.get("title"),1,512),text(node.get("reason"),1,2048));
            }
            result=new ReportBody(title,List.copyOf(sections),citations,type,start,end,"Asia/Shanghai",task);
        } else {
            if(!kind.equals(WireValues.text(body.get("category"))))throw invalid();
            result=new InterpretationBody(title,List.copyOf(sections),citations,kind,WireValues.id(body.get("entity_id"),false));
        }
        if(!Arrays.equals(CanonicalJson.bytes(body),CanonicalJson.bytes(PublishedContentFacade.body(result))))throw invalid();
        return result;
    }
    private static List<String> ids(JsonNode values) {
        if(values==null||!values.isArray()||values.size()>1000)throw invalid();var ids=new ArrayList<String>();
        for(var value:values)ids.add(WireValues.id(value,false));return List.copyOf(ids);
    }
    private static String text(JsonNode node,int min,int max){String value=WireValues.text(node);int size=value.codePointCount(0,value.length());if(size<min||size>max)throw invalid();return value;}
    private static void closed(JsonNode node,String... names){var fields=Set.of(names);WireValues.object(node,fields,fields);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid frozen content body");}
}
