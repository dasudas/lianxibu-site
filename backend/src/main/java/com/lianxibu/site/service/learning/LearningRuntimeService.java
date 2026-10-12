package com.lianxibu.site.service.learning;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.learning.JdbcLearningAnswers;
import com.lianxibu.site.repository.learning.JdbcLearningImports;
import com.lianxibu.site.repository.learning.JdbcLearningPackages;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistent learning composition; public reads and owner mutations have separate transactions. */
public final class LearningRuntimeService implements LearningService {
    private final LearningReadService base;private final JdbcLearningAnswers answers;
    private final JdbcLearningPackages packages;private final JdbcLearningImports imports;
    private final FileLearningImportStages staging;private final SharedWriteExecutor writes;private final Clock clock;
    private final TransactionTemplate reads;
    public LearningRuntimeService(LearningReadService base,JdbcLearningAnswers answers,JdbcLearningPackages packages,
            JdbcLearningImports imports,FileLearningImportStages staging,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){
        this.base=base;this.answers=answers;this.packages=packages;this.imports=imports;this.staging=staging;this.writes=writes;this.clock=clock;
        reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private <T>T read(Supplier<T> work){return reads.execute(ignored->work.get());}
    private PackageSummary metadata(PackageSummary p){return new PackageSummary(p.id(),p.title(),description(p.id(),p.revision()),p.revision(),p.state());}
    private String description(String pkg,long revision){String raw=imports.metadata(pkg,revision);if(raw==null)return null;var value=CanonicalJson.parse(raw);if(!pkg.equals(value.path("package_id").asText()))throw unavailable();return value.get("description").asText();}
    @Override public Page<PackageSummary> packages(long page,int size){return read(()->{var p=base.packages(page,size);return new Page<>(p.items().stream().map(this::metadata).toList(),page,size,p.total());});}
    @Override public PackageSummary packageSummary(String pkg){return read(()->metadata(base.packageSummary(pkg)));}
    @Override public Page<Route> routes(String pkg,Query q){return read(()->base.routes(pkg,q));}
    @Override public Page<Module> modules(String pkg,Query q){return read(()->base.modules(pkg,q));}
    @Override public Page<Node> nodes(String pkg,Query q){return read(()->base.nodes(pkg,q));}
    @Override public Page<Project> projects(String pkg,Query q){return read(()->base.projects(pkg,q));}
    @Override public Page<Question> questions(String pkg,Query q){return read(()->{var p=base.questions(pkg,q);return new Page<>(p.items().stream().map(this::reviewed).toList(),p.page(),p.pageSize(),p.total());});}
    @Override public Node node(String pkg,String id){return read(()->base.node(pkg,id));}
    @Override public Project project(String pkg,String id){return read(()->base.project(pkg,id));}
    @Override public Question question(String pkg,String id){return read(()->reviewed(base.question(pkg,id)));}
    @Override public Graph graph(String pkg,String module,String cursor){return read(()->base.graph(pkg,module,cursor));}
    @Override public ResolvedPackage resolve(String kind,String id){return read(()->base.resolve(kind,id));}
    private Question reviewed(Question value){
        if(!"published".equals(value.answerState()))return value;
        var row=answers.read(value.packageId(),value.id(),false);
        if(row==null||!row.packagePublished()||row.revision()!=value.revision()||row.answer()==null||row.reviewHash()==null
                ||!Arrays.equals(row.reviewHash(),AnswerLifecycleService.answerHash(row.answer())))throw unavailable();
        return value;
    }
    public OwnerPackageReadService.Snapshot ownerPackage(String id){return read(()->{var row=packages.read(id,false);return row==null?null:new OwnerPackageReadService.Snapshot(id,row.title(),description(id,row.revision()),row.revision(),row.state());});}
    public OwnerAnswerReadService.Snapshot ownerAnswer(String pkg,String id){return read(()->{var row=answers.read(pkg,id,false);return row==null?null:new OwnerAnswerReadService.Snapshot(pkg,id,row.title(),row.revision(),row.answerRevision(),row.state(),row.generationState(),row.answer(),true,row.reviewHash(),row.packagePublished(),true);});}
    @Override public PackageImportResult createPackage(Actor actor,PackageCommand command,String key,String csrf,String origin){return importPackage(actor,null,command,key);}
    @Override public PackageImportResult writePackage(Actor actor,String pkg,PackageCommand command,String key,String csrf,String origin){return importPackage(actor,pkg,command,key);}
    private PackageImportResult importPackage(Actor actor,String pkg,PackageCommand command,String key){
        owner(actor);var service=new LearningImportService(staging,new LearningImportJdbcStore(imports,packages,actor.userId(),clock),writes,clock);
        if(command instanceof Stage stage){var result=service.stage(actor.userId(),pkg,stage.chunk());return new ImportProgress(result.importId(),result.acceptedPartNo(),result.partHash(),result.expiresAt());}
        if(command instanceof Commit commit){var result=service.commit(actor.userId(),pkg,commit.expectedRevision(),commit.manifest(),new ActionContext(key,UUID.randomUUID().toString(),null));
            // The command receipt carries historical response metadata even after a later package change.
            return new PackageSummaryResult(new PackageSummary(result.packageId(),commit.manifest().title(),commit.manifest().description(),result.revision(),"draft"));}
        throw new IllegalArgumentException("invalid import command");
    }
    @Override public ActionResult packageAction(Actor actor,String pkg,AnswerAction command,String key,String csrf,String origin){
        owner(actor);var service=new PackageLifecycleService(writes,new PackageJdbcStore(packages,actor.userId(),clock));String op=JdbcLearningPackages.actionId(actor.userId(),key);
        var result=service.action(actor,pkg,command.expectedRevision(),PackageLifecycleService.Action.valueOf(command.action().toUpperCase(Locale.ROOT)),command.reason(),new ActionContext(key,op,null));
        return result(op,"package",pkg,null,result.revision());
    }
    @Override public ActionResult writeAnswer(Actor actor,String pkg,String id,AnswerWrite command,String key,String csrf,String origin){
        owner(actor);String op=JdbcLearningAnswers.actionId(actor.userId(),key);var result=answerService(actor).edit(pkg,id,command.expectedRevision(),command.answer(),new ActionContext(key,op,null));return result(op,"question",id,pkg,result.revision());
    }
    @Override public ActionResult answerAction(Actor actor,String pkg,String id,AnswerAction command,String key,String csrf,String origin){
        owner(actor);String op=JdbcLearningAnswers.actionId(actor.userId(),key);var result=answerService(actor).action(pkg,id,command.expectedRevision(),AnswerLifecycleService.Action.valueOf(command.action().toUpperCase(Locale.ROOT)),command.reason(),new ActionContext(key,op,null));return result(op,"question",id,pkg,result.revision());
    }
    private AnswerLifecycleService answerService(Actor actor){return new AnswerLifecycleService(writes,new AnswerJdbcStore(answers,actor.userId(),clock));}
    private static ActionResult result(String op,String kind,String id,String pkg,long revision){return new ActionResult(op,new ResourceRef(kind,id,pkg),revision,null,null,null,List.of(),null);}
    private static void owner(Actor actor){if(actor==null)throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
    private static IdentityService.Rejected unavailable(){return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
}
