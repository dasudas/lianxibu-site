package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.JdbcEntityHeatEventLocators;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** UUID order is never a commit watermark. A completed scan always restarts from the beginning. */
public final class EntityHeatEventAvailabilityPoller {
    public record Outcome(int scanned,int observed,int failed){ }
    private final JdbcEntityHeatEventLocators locators;private final EntityHeatEventAvailabilityService observations;private final TransactionTemplate reads;private final int limit;private String cursor="";
    public EntityHeatEventAvailabilityPoller(JdbcEntityHeatEventLocators locators,EntityHeatEventAvailabilityService observations,PlatformTransactionManager tx,int limit){this.locators=Objects.requireNonNull(locators);this.observations=Objects.requireNonNull(observations);if(limit<1||limit>64)throw invalid();this.limit=limit;reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public synchronized Outcome poll(){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();var page=Objects.requireNonNull(reads.execute(s->locators.page(cursor,limit)));int observed=0,failed=0;for(String id:page.ids()){try{if(observations.capture(id)!=null)observed++;}catch(RuntimeException unavailable){failed++;}}cursor=page.hasMore()?page.ids().getLast():"";return new Outcome(page.ids().size(),observed,failed);}
    private static IllegalStateException invalid(){return new IllegalStateException("entity count observation cycle unavailable");}
}
