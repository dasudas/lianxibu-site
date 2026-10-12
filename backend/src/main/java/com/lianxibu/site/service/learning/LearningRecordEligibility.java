package com.lianxibu.site.service.learning;

import com.lianxibu.site.repository.learning.JdbcLearningReads;
import java.util.ArrayList;
import java.util.List;

/** Uses the same published-package and graph membership rules as current learning reads. */
public final class LearningRecordEligibility {
    private final JdbcLearningReads reads;
    public LearningRecordEligibility(JdbcLearningReads reads){this.reads=java.util.Objects.requireNonNull(reads);}
    public Long revision(String pkg,String kind,String id){var row=reads.byId(pkg,kind,id);return row==null?null:row.revision();}
    public List<String> routeNodes(String pkg,String id){
        if(reads.byId(pkg,"route",id)==null)return List.of();
        var result=new ArrayList<String>();
        for(int page=1;page<=200;page++){
            var batch=reads.items(pkg,"node",id,null,null,null,null,null,page,50);
            for(var row:batch.items())result.add(row.id());
            if((long)page*50>=batch.total())return List.copyOf(result);
        }
        throw new IllegalStateException("route membership exceeds bounded read");
    }
}
