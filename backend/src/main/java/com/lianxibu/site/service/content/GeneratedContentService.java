package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.content.JdbcGeneratedContents;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Narrow content replacement port. Jobs owns input/result proofs and downstream work. */
public final class GeneratedContentService {
    public record Write(String kind,String id,Long expectedRevision,String inputOwner,String bodyJson) { }
    private static final Set<String> KINDS=Set.of("report","agent","skill","workflow","model");
    private final JdbcGeneratedContents store;
    private final Clock clock;
    public GeneratedContentService(JdbcGeneratedContents store,Clock clock){this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);}
    public JdbcGeneratedContents.Applied write(Write command) {
        if(command==null||!KINDS.contains(command.kind())||command.expectedRevision()!=null&&(command.expectedRevision()<1||command.expectedRevision()>=CanonicalJson.MAX_SAFE_INTEGER))throw invalid();
        WireValues.id(command.id(),false);WireValues.id(command.inputOwner(),false);
        var body=CanonicalJson.parse(command.bodyJson());var fields=new HashSet<>(Set.of("title","sections","citation_ids"));
        if(command.kind().equals("report"))fields.addAll(Set.of("type","period_start","period_end","timezone","learning_task"));else fields.addAll(Set.of("category","entity_id"));
        WireValues.object(body,fields,fields);String title=text(body.get("title"),1,512);ids(body.get("citation_ids"));var sections=body.get("sections");if(!sections.isArray()||sections.size()>100)throw invalid();
        for(var section:sections){var required=Set.of("heading","text","citation_ids");WireValues.object(section,required,required);text(section.get("heading"),1,512);text(section.get("text"),0,200000);ids(section.get("citation_ids"));}
        String entity=null;byte[] period=null;
        if(command.kind().equals("report")) {
            String type=WireValues.text(body.get("type"));if(!Set.of("daily","weekly","monthly").contains(type)||!"Asia/Shanghai".equals(WireValues.text(body.get("timezone"))))throw invalid();
            var first=WireValues.utcMicroseconds(body.get("period_start"));var last=WireValues.utcMicroseconds(body.get("period_end"));if(!first.isBefore(last))throw invalid();
            var learning=body.get("learning_task");if(!learning.isNull()){if(!type.equals("daily"))throw invalid();var required=Set.of("package_id","object_kind","object_id","title","reason");WireValues.object(learning,required,required);WireValues.id(learning.get("package_id"),false);WireValues.id(learning.get("object_id"),false);if(!Set.of("node","question","project").contains(WireValues.text(learning.get("object_kind"))))throw invalid();text(learning.get("title"),1,512);text(learning.get("reason"),1,2048);}
            var key=JsonMapper.shared().createObjectNode();key.put("kind","report");for(String f:List.of("type","period_start","period_end","timezone"))key.set(f,body.get(f));period=HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.CONTENT_KEY,key));
        }else{if(!command.kind().equals(WireValues.text(body.get("category"))))throw invalid();entity=WireValues.id(body.get("entity_id"),false);}
        byte[] bytes=CanonicalJson.bytes(body);if(bytes.length>262144)throw invalid();
        return store.replace(command.kind(),command.id(),command.expectedRevision(),title,new String(bytes,StandardCharsets.UTF_8),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body)),command.inputOwner(),entity,period,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    private static String text(JsonNode node,int minimum,int maximum){String s=WireValues.text(node);if(s.codePointCount(0,s.length())<minimum||s.codePointCount(0,s.length())>maximum)throw invalid();CanonicalJson.bytes(node);return s;}
    private static void ids(JsonNode node){if(!node.isArray()||node.size()>1000)throw invalid();for(var id:node)WireValues.id(id,false);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid generated content");}
}
