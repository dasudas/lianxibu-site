package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.EntityAnalysisStateService;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Completes the exact reserved analysis generation in the result writer transaction. */
public final class AnalysisResultDownstream implements ContentResultHandler.Downstream {
    private final JdbcFrozenInputs inputs;
    private final ContentReadService contents;
    private final EntityAnalysisStateService entities;
    public AnalysisResultDownstream(JdbcFrozenInputs inputs,ContentReadService contents,EntityAnalysisStateService entities) {
        this.inputs=Objects.requireNonNull(inputs);this.contents=Objects.requireNonNull(contents);this.entities=Objects.requireNonNull(entities);
    }
    @Override public void afterApplied(Input input,FrozenApplicationContext context,Validated result,Applied applied) {
        JdbcFrozenInputs.requireTx();
        if(input==null||input.stage()==null||!input.stage().analysis()||context==null||!context.referencesComplete()||result==null||applied==null||!"analysis".equals(result.kind()))throw invalid();
        var snapshot=CanonicalJson.parse(inputs.read(input.rootId(),input.inputHash()));
        var original=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        if(!original.json().equals(context.json())||!input.equals(original.input(input.rootId(),input.attemptId(),input.inputHash(),input.expectedRootRevision()))
            ||!context.contentId().equals(applied.contentId())||applied.contentRevision()==null||applied.contentRevision()<1)throw invalid();
        var payload=CanonicalJson.parse(result.payloadJson());
        var envelope=JsonMapper.shared().createObjectNode().put("schema_version","1").put("input_hash",result.inputHash()).put("result_hash",result.resultHash());envelope.set("payload",payload);
        if(!result.equals(ResultApplicationService.validate(input,envelope.toString())))throw invalid();
        var body=payload.get("body");var current=contents.administrativeVersion(context.contentKind(),context.contentId()).orElseThrow(AnalysisResultDownstream::invalid);
        if(!context.contentKind().equals(current.kind())||!context.contentId().equals(current.id())||current.revision()!=applied.contentRevision()
            ||!input.rootId().equals(current.inputJobId())||!"draft".equals(current.state())||current.bodyHash()==null
            ||!CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body).equals(HexFormat.of().formatHex(current.bodyHash()))
            ||!Arrays.equals(CanonicalJson.bytes(body),CanonicalJson.bytes(CanonicalJson.parse(current.bodyJson()))))throw invalid();
        entities.finish(input.targetEntityId(),context.contentKind(),input.generation(),true);
    }
    private static Rejected invalid(){return new Rejected();}
}
