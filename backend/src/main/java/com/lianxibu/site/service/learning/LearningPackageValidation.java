package com.lianxibu.site.service.learning;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.learning.JdbcLearningPackages.Item;
import com.lianxibu.site.repository.learning.JdbcLearningPackages.Link;
import java.net.URI;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Complete current package validation, independent of import history. */
public final class LearningPackageValidation {
    private LearningPackageValidation() { }
    public static boolean valid(List<Item> items,List<Link> links) {
        try {
            if(items.isEmpty())return false;
            var ids=new HashSet<String>();var nodes=new HashSet<String>();
            for(var item:items) {
                if(!Set.of("route","module","node","question","project","resource").contains(item.kind()))return false;
                WireValues.id(item.id(),false);
                if(item.title()==null||item.title().isBlank()||item.title().codePointCount(0,item.title().length())>200||!ids.add(key(item.kind(),item.id())))return false;
                JsonNode body=CanonicalJson.parse(item.body());if(!body.isObject())return false;
                for(String name:List.of("goals_raw","completion_criteria_raw","assessment_points_raw","requirements_raw","tasks_raw","deliverables_raw","acceptance_criteria_raw")) if(body.has(name)&&!ordered(body.get(name)))return false;
                if(item.kind().equals("node"))nodes.add(item.id());
                if(item.kind().equals("resource")) {
                    URI url=URI.create(WireValues.text(body.get("url")));
                    if(!"https".equals(url.getScheme())||url.getHost()==null||url.getUserInfo()!=null)return false;
                }
                if(item.kind().equals("question") && (WireValues.text(body.get("question")).isBlank()
                        || !Set.of("beginner","intermediate","advanced").contains(WireValues.text(body.get("difficulty")))
                        || !Set.of("concept","principle","code","scenario","system_design").contains(WireValues.text(body.get("question_kind")))))return false;
            }
            var parents=new HashMap<String,Integer>();var edges=new HashMap<String,Set<String>>();var seen=new HashSet<Link>();
            for(var link:links) {
                if(!seen.add(link)||!ids.contains(key(link.fromKind(),link.fromId()))||!ids.contains(key(link.toKind(),link.toId())))return false;
                String shape=link.fromKind()+"/"+link.relation()+"/"+link.toKind();
                if(!Set.of("route/contains/module","module/contains/node","node/requires/node","node/related/node","node/contains/node","node/references/resource","node/asks/question","node/practices/project").contains(shape))return false;
                if(shape.equals("module/contains/node"))parents.merge(link.toId(),1,Integer::sum);
                if(shape.equals("node/requires/node")||shape.equals("node/contains/node"))edges.computeIfAbsent(link.fromId(),k->new HashSet<>()).add(link.toId());
            }
            for(String id:nodes)if(parents.getOrDefault(id,0)!=1)return false;
            // Kahn's algorithm avoids stack overflow on a long, valid dependency chain.
            var degree=new HashMap<String,Integer>();nodes.forEach(n->degree.put(n,0));
            edges.values().forEach(next->next.forEach(n->degree.merge(n,1,Integer::sum)));
            var queue=new ArrayDeque<String>();degree.forEach((n,d)->{if(d==0)queue.add(n);});int visited=0;
            while(!queue.isEmpty()){String id=queue.remove();visited++;for(String next:edges.getOrDefault(id,Set.of()))if(degree.merge(next,-1,Integer::sum)==0)queue.add(next);}
            return visited==nodes.size();
        } catch(IllegalArgumentException|NullPointerException invalid) { return false; }
    }
    private static String key(String kind,String id){return kind+":"+id;}
    private static boolean ordered(JsonNode values){
        if(values==null||!values.isArray())return false;var positions=new HashSet<Long>();int position=0;
        for(var item:values){long p=position++;
            if(item.isString()){if(item.asText().isBlank())return false;}
            else if(item.isObject()&&item.has("position")&&item.has("value")){p=WireValues.nonnegativeInteger(item.get("position"));if(WireValues.text(item.get("value")).isBlank())return false;}
            else return false;
            if(!positions.add(p))return false;
        }return true;
    }
}
