package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.sources.SourceDayCollectionAdmission;
import java.time.LocalDate;
import java.util.*;

/** Read-only full original seal bridge; no run receipt or claim is asserted by this provider. */
public final class SourceDayCollectionAdmissionService implements SourceDayCollectionAdmission.Api {
    private final SourceDayCohortSealService seals;
    public SourceDayCollectionAdmissionService(SourceDayCohortSealService seals){this.seals=Objects.requireNonNull(seals);}
    @Override public SourceDayCollectionAdmission.SealedDay read(LocalDate day){
        var original=seals.find(day);if(original==null)return null;var plan=original.plan();var directory=plan.directory();var counts=new LinkedHashMap<String,Long>();
        for(var cohort:plan.cohorts())counts.put(cohort.category(),(long)cohort.members().size());
        return new SourceDayCollectionAdmission.SealedDay(day,directory.evidence().hash(),plan.bundleHash(),directory.registeredAt(),original.sealedAt(),directory.evidence().sourceCount(),plan.unassignedSourceIds().size(),counts,plan.requests());
    }
    @Override public String toString(){return "SourceDayCollectionAdmissionService[redacted]";}
}
