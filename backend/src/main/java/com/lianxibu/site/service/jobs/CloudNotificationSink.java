package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.jobs.OperationalNotification.Sender;
import java.io.IOException;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable safe local log first, then one platform call outside the writer transaction. */
public final class CloudNotificationSink implements OperationalNoticeDelivery.Sink {
    private final OperationalNoticeDelivery.Sink log;private final Sender sender;
    public CloudNotificationSink(OperationalNoticeDelivery.Sink log,Sender sender){this.log=Objects.requireNonNull(log);this.sender=Objects.requireNonNull(sender);}
    @Override public void append(OperationalNoticeDelivery.Event event)throws Exception {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("notification outside transaction required");
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        var payload=OperationalNotificationRenderer.render(event);log.append(event);
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(sender.send(payload)==null)throw new IOException("cloud notification unavailable");
    }
}
