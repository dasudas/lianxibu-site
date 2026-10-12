package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.JdbcEntityAnalysisState;
import java.util.*;

/** Caller must atomically bind eligibility and permanent job intent to a reservation. */
public final class EntityAnalysisStateService {
    public record State(String entityId,String category,long revision,long generation,String state) { }
    private final JdbcEntityAnalysisState store;
    public EntityAnalysisStateService(JdbcEntityAnalysisState store){this.store=Objects.requireNonNull(store);}
    public State reserve(String id,String category,long expectedRevision,long expectedGeneration){
        var current=required(id,category);
        if(current.revision()!=expectedRevision||current.generation()!=expectedGeneration||current.generation()>=CanonicalJson.MAX_SAFE_INTEGER
            ||"needs_review".equals(current.state())||("pending".equals(current.state())!=(current.generation()==0)))throw invalid();
        return change(current,current.generation()+1,"queued");
    }
    public State finish(String id,String category,long generation,boolean success){
        var current=required(id,category);if(generation<1||current.generation()!=generation||!"queued".equals(current.state()))throw invalid();
        return change(current,generation,success?"analyzed":"failed");
    }
    private JdbcEntityAnalysisState.Row required(String id,String category){
        WireValues.id(id,false);if(!Set.of("agent","model","skill","workflow").contains(category))throw invalid();var row=store.lock(id);
        if(row==null||!id.equals(row.id())||!category.equals(row.category())||row.legacy()||row.revision()<0||row.revision()>=CanonicalJson.MAX_SAFE_INTEGER||row.generation()<0||row.generation()>CanonicalJson.MAX_SAFE_INTEGER||row.state()==null||!Set.of("pending","queued","analyzed","failed","needs_review").contains(row.state()))throw invalid();return row;
    }
    private State change(JdbcEntityAnalysisState.Row current,long generation,String state){if(!store.change(current,generation,state))throw invalid();return new State(current.id(),current.category(),current.revision()+1,generation,state);}
    private static IllegalStateException invalid(){return new IllegalStateException("entity analysis identity or generation conflict");}
}
