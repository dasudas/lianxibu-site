package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.sources.JdbcSourceDirectory;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Configuration commands only. Production admission remains a separately reviewed host operation. */
public final class SourceRegistryService implements SourcesService {
    public record BoundCommand(SourceAdmissionService service,SourceCommandJdbcReceipts receipts) { }
    @FunctionalInterface public interface Commands {BoundCommand bind(Actor actor,String key,JsonNode command);}
    private final JdbcSourceDirectory directory;private final OwnerSourceReadService sources;private final Commands commands;private final TransactionTemplate reads;
    private final SourceRunReadService runs;
    private final OwnerRunCreateService runCommands;
    public SourceRegistryService(JdbcSourceDirectory directory,OwnerSourceReadService sources,Commands commands,PlatformTransactionManager tx){
        this(directory,sources,commands,tx,null);
    }
    public SourceRegistryService(JdbcSourceDirectory directory,OwnerSourceReadService sources,Commands commands,PlatformTransactionManager tx,SourceRunReadService runs){
        this(directory,sources,commands,tx,runs,null);
    }
    public SourceRegistryService(JdbcSourceDirectory directory,OwnerSourceReadService sources,Commands commands,PlatformTransactionManager tx,SourceRunReadService runs,OwnerRunCreateService runCommands){
        this.runs=runs;
        this.runCommands=runCommands;
        this.directory=directory;this.sources=sources;this.commands=commands;reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public SourcePage listSources(Actor actor,long page,int size){owner(actor);if(page<1||page>CanonicalJson.MAX_SAFE_INTEGER||size<1||size>50)throw new IllegalArgumentException("invalid page");return reads.execute(tx->{var result=directory.page(page,size);if(result.total()<0||result.total()>CanonicalJson.MAX_SAFE_INTEGER)throw unavailable();return new SourcePage(result.ids().stream().map(id->project(sources.read(actor,id))).toList(),page,size,result.total());});}
    @Override public Source createSource(Actor actor,SourceCreate value,String key,String csrf,String origin){
        owner(actor);if(value==null)throw new IllegalArgumentException("source command required");var bound=commands.bind(actor,key,command("create",value.id(),value));
        bound.service().create(actor,value,action(key));return project(bound.receipts().originalSource());
    }
    @Override public Source writeSource(Actor actor,String id,SourceWrite value,String key,String csrf,String origin){
        owner(actor);WireValues.id(id,true);if(value==null)throw new IllegalArgumentException("source command required");var bound=commands.bind(actor,key,command("edit",id,value));
        bound.service().edit(actor,id,value,action(key));return project(bound.receipts().originalSource());
    }
    @Override public ActionResult sourceAction(Actor actor,String id,SourceAction value,String key,String csrf,String origin){
        owner(actor);WireValues.id(id,true);if(value==null)throw new IllegalArgumentException("source command required");
        if(!"disable".equals(value.action()))throw unavailable();
        if(value.reason()==null||value.reason().isBlank()||value.reason().codePointCount(0,value.reason().length())>2048)throw new IllegalArgumentException("source reason required");
        var bound=commands.bind(actor,key,command("disable",id,value));var result=bound.service().disable(actor,id,value.expectedRevision(),action(key));
        return new ActionResult(bound.receipts().operationId(),new ResourceRef("source",id,null),result.revision(),null,null,null,List.of(),null);
    }
    private static JsonNode command(String operation,String id,Object value){var root=JsonMapper.shared().createObjectNode();root.put("operation",operation);root.put("source_id",id);root.set("command",JsonMapper.shared().valueToTree(value));return root;}
    private static ActionContext action(String key){return new ActionContext(key,UUID.randomUUID().toString(),null);}
    private static Source project(Map<String,Object> value){
        JsonNode n=JsonMapper.shared().valueToTree(value);String id=WireValues.id(n.get("id"),true);OwnerSourceReadService.validateProjection(value,id);
        return new Source(id,n.get("name").asText(),n.get("category").asText(),n.get("revision").longValue(),n.get("config_revision").longValue(),n.get("activation_stage").asText(),config(n.get("draft_config")),config(n.get("effective_config")),n.get("config_valid_until").isNull()?null:Instant.parse(n.get("config_valid_until").asText()));
    }
    private static SourceConfig config(JsonNode n){if(n.isNull())return null;return new SourceConfig(n.get("adapter").asText(),strings(n.get("entry_urls")),n.get("identity_rule").asText(),n.get("window_rule").asText(),n.get("parser_version").asText(),n.get("max_items").longValue(),n.get("max_response_bytes").longValue(),strings(n.get("allowed_hosts")),Instant.parse(n.get("valid_until").asText()),n.get("auth_secret_ref").isNull()?null:n.get("auth_secret_ref").asText());}
    private static List<String> strings(JsonNode n){var out=new java.util.ArrayList<String>();n.forEach(v->out.add(v.asText()));return List.copyOf(out);}
    private static void owner(Actor actor){if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
    private static IdentityService.Rejected unavailable(){return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
    @Override public RunPage listRuns(Actor a,String id,LocalDate day,long page,int size){owner(a);if(runs==null)throw unavailable();return runs.listRuns(a,id,day,page,size);}
    @Override public Run run(Actor a,String id){owner(a);if(runs==null)throw unavailable();return runs.run(a,id);}
    @Override public RunItemPage listItems(Actor a,String id,long page,int size){owner(a);if(runs==null)throw unavailable();return runs.listItems(a,id,page,size);}
    @Override public Run createRun(Actor a,RunCreate c,String key,String csrf,String origin){owner(a);if(runCommands==null)throw unavailable();return runCommands.create(a,c,key);}
    @Override public ItemActionResult itemAction(Actor a,String run,String id,ItemAction c,String key,String csrf,String origin){owner(a);throw unavailable();}
}
