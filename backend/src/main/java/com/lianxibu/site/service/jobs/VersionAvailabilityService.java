package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.ContentVersionKey;
import com.lianxibu.site.repository.jobs.JdbcVersionAvailability;
import java.time.*;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read committed proof outside any writer, then permanently record the observed upper bound. */
public final class VersionAvailabilityService {
    private final FrozenContentVersionService versions;
    private final JdbcVersionAvailability store;
    private final SharedWriteExecutor writes;
    private final Clock clock;
    private final TransactionTemplate reads;
    public VersionAvailabilityService(FrozenContentVersionService versions,JdbcVersionAvailability store,SharedWriteExecutor writes,PlatformTransactionManager tx,Clock clock){
        this.versions=Objects.requireNonNull(versions);this.store=Objects.requireNonNull(store);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);
        reads=new TransactionTemplate(Objects.requireNonNull(tx));
        reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Instant capture(ContentVersionKey target){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("version observation must follow the original commit");
        var proof=Objects.requireNonNull(reads.execute(status->versions.find(target).orElseThrow(()->new IllegalStateException("committed version proof unavailable"))));
        Instant now=clock.instant();int remainder=now.getNano()%1000;Instant observed=remainder==0?now:now.plusNanos(1000-remainder);
        return writes.execute(new SharedWriteExecutor.ActionContext(JdbcVersionAvailability.key(target),target.contentId(),proof.inputHash()),
            key->store.read(target,proof.inputHash()),action->{store.save(target,proof.inputHash(),observed);return observed;});
    }
}
