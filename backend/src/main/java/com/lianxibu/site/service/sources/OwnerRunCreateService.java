package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.sources.SourcesService.*;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import tools.jackson.databind.json.JsonMapper;

/** Explicit production collection only. Owner identity, source CAS and the original response are permanent. */
public final class OwnerRunCreateService {
    private final JdbcSourceRunStore runs;
    private final JdbcSourceAdmission sources;
    private final JdbcOwnerRunReceipts receipts;
    private final SharedWriteExecutor writes;
    private final SourceRunReadService reads;
    private final Clock clock;
    private final Predicate<String> allowed;
    public OwnerRunCreateService(JdbcSourceRunStore runs,JdbcSourceAdmission sources,JdbcOwnerRunReceipts receipts,
            SharedWriteExecutor writes,SourceRunReadService reads,Clock clock,Predicate<String> allowed){
        this.runs=Objects.requireNonNull(runs);this.sources=Objects.requireNonNull(sources);this.receipts=Objects.requireNonNull(receipts);
        this.writes=Objects.requireNonNull(writes);this.reads=Objects.requireNonNull(reads);this.clock=Objects.requireNonNull(clock);this.allowed=Objects.requireNonNull(allowed);
    }
    public Run create(Actor actor,RunCreate command,String key){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw rejected(IdentityService.Refusal.UNAUTHENTICATED);
        if(!"owner".equals(actor.role()))throw rejected(IdentityService.Refusal.FORBIDDEN);
        validate(command,key);
        var request=JsonMapper.shared().valueToTree(command);String hash=CanonicalJson.hash(CanonicalJson.Domain.ACTION,request);
        String runId=UUID.randomUUID().toString();
        try{return writes.execute(new SharedWriteExecutor.ActionContext(key,runId,hash),ignored->{
            var receipt=receipts.read(actor.userId(),key);if(receipt==null)return null;
            if(!hash.equals(receipt.commandHash()))throw rejected(IdentityService.Refusal.IDENTITY_CONFLICT);
            var result=JsonMapper.shared().treeToValue(CanonicalJson.parse(receipt.response()),Run.class);
            if(result==null||!command.sourceId().equals(result.sourceId())||!command.runKind().equals(result.runKind())
                    ||!command.businessDate().equals(result.businessDate())||!command.windowStart().equals(result.windowStart())
                    ||!command.windowEnd().equals(result.windowEnd())||result.configVersion()!=command.configVersion()
                    ||!"pending".equals(result.deliveryState())||result.runRevision()!=0||!"materialize".equals(result.fetchMode()))throw new IllegalStateException("run response proof unavailable");
            return result;
        },action->{
            var source=sources.locked(command.sourceId());if(source==null)throw rejected(IdentityService.Refusal.NOT_FOUND);
            if(source.revision()!=command.expectedRevision()||source.configRevision()!=command.configVersion()
                    ||!"production".equals(source.activationStage())||source.effectiveConfigJson()==null
                    ||source.configValidUntil()==null||!source.configValidUntil().isAfter(clock.instant()))throw rejected(IdentityService.Refusal.REVISION_CONFLICT);
            var config=CanonicalJson.parse(source.effectiveConfigJson());
            if(!Instant.parse(WireValues.text(config.get("valid_until"))).isAfter(clock.instant()))throw rejected(IdentityService.Refusal.REVISION_CONFLICT);
            if(!allowed.test(command.sourceId()))throw rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE);
            long generation=runs.nextGeneration(command.sourceId(),command.runKind(),command.businessDate());
            if(generation<1||generation>CanonicalJson.MAX_SAFE_INTEGER)throw new IllegalStateException("run generations exhausted");
            runs.insert(new JdbcSourceRunStore.NewRun(runId,command.sourceId(),command.runKind(),command.businessDate(),generation,
                command.windowStart(),command.windowEnd(),null,source.configRevision(),source.effectiveConfigJson(),"production",
                "materialize",command.runKind(),clock.instant()));
            Run result=reads.run(actor,runId);String response=JsonMapper.shared().writeValueAsString(result);
            receipts.save(actor.userId(),key,hash,request.toString(),runId,response,clock.instant());return result;
        });}catch(IdentityService.Rejected known){throw known;}catch(RuntimeException unavailable){throw rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE);}
    }
    private void validate(RunCreate c,String key){
        if(c==null||key==null||!key.matches("[A-Za-z0-9._:-]{1,128}")||c.businessDate()==null||c.windowStart()==null||c.windowEnd()==null
                ||!Set.of("historical_backfill","production_qualification").contains(c.runKind())||c.configVersion()<0||c.configVersion()>CanonicalJson.MAX_SAFE_INTEGER
                ||c.expectedRevision()<0||c.expectedRevision()>CanonicalJson.MAX_SAFE_INTEGER||c.reason()==null||c.reason().isBlank()||c.reason().codePointCount(0,c.reason().length())>2048)
            throw new IllegalArgumentException("invalid run creation");
        WireValues.id(c.sourceId(),true);var start=c.businessDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        if(c.windowStart().isBefore(start)||c.windowEnd().isAfter(start.plusSeconds(86400))||!c.windowStart().isBefore(c.windowEnd())
                ||c.windowEnd().isAfter(clock.instant())||c.windowStart().getNano()%1000!=0||c.windowEnd().getNano()%1000!=0)throw new IllegalArgumentException("invalid run window");
    }
    private static IdentityService.Rejected rejected(IdentityService.Refusal reason){return new IdentityService.Rejected(reason,reason==IdentityService.Refusal.TEMPORARILY_UNAVAILABLE?"1":null);}
}
