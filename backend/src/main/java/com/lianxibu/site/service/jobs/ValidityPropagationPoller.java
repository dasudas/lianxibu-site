package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcValidityPropagation;
import java.util.Objects;
import java.util.function.Supplier;

/** One bounded database step; the durable queue, not this object, owns progress. */
public final class ValidityPropagationPoller {
    public enum Outcome { IDLE, WORKED }
    private final JdbcValidityPropagation queue;private final Supplier<VersionValidityService> services;private final Supplier<String> ids;
    public ValidityPropagationPoller(JdbcValidityPropagation queue,Supplier<VersionValidityService> services,Supplier<String> ids){this.queue=Objects.requireNonNull(queue);this.services=Objects.requireNonNull(services);this.ids=Objects.requireNonNull(ids);}
    public Outcome poll(){
        if(!queue.hasQueued())return Outcome.IDLE;
        String id=WireValues.id(ids.get(),false);
        return services.get().propagateNext(new SharedWriteExecutor.ActionContext("validity-step:"+id,id,null))?Outcome.WORKED:Outcome.IDLE;
    }
}
