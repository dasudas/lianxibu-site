package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.JdbcEntityDedup;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current locked assignments used only while preparing a new immutable input. */
public final class EntityInputEvidenceService {
    public record Selection(String entityId,String category,long entityRevision,List<SourceMemberEvidenceService.Fact> members) {
        public Selection { members=List.copyOf(members); }
    }
    private final JdbcEntityDedup entities;
    private final SourceMemberEvidenceService evidence;
    public EntityInputEvidenceService(JdbcEntityDedup entities,SourceMemberEvidenceService evidence){this.entities=Objects.requireNonNull(entities);this.evidence=Objects.requireNonNull(evidence);}
    public Selection read(String entityId,String category,Set<String> ids,Instant cutoff) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw invalid();
        WireValues.id(entityId,false);if(!Set.of("news_tech","agent","model","skill","workflow").contains(category))throw invalid();
        var entity=entities.entity(entityId);
        if(entity==null||!entityId.equals(entity.id())||!category.equals(entity.category())||entity.legacy()||entity.identityKey()==null||entity.identityKey().isBlank()
            ||entity.revision()<0||entity.revision()>=CanonicalJson.MAX_SAFE_INTEGER||!Arrays.equals(entity.identityHash(),JdbcEntityDedup.identityHash(category,entity.identityKey())))throw invalid();
        var facts=evidence.read(ids,cutoff);
        for(var fact:facts) {
            var member=entities.member(fact.id());
            if(member==null||!entityId.equals(member.entityId())||!"ready".equals(member.state())||!category.equals(fact.category())||!category.equals(member.category())||member.revision()!=fact.revision())throw invalid();
        }
        return new Selection(entityId,category,entity.revision(),facts);
    }
    private static IllegalStateException invalid(){return new IllegalStateException("entity input evidence unavailable");}
}
