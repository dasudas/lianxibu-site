package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.jobs.OperationalNotification;
import com.lianxibu.site.domain.jobs.OperationalNotification.Payload;
import java.util.Objects;
import java.util.Set;

/** Uses validated immutable notice fields only; model text and exception diagnostics have no input here. */
public final class OperationalNotificationRenderer {
    private OperationalNotificationRenderer() { }
    public static Payload render(OperationalNoticeDelivery.Event e) {
        Objects.requireNonNull(e);
        if(!Set.of("trigger","upgrade","reminder","resolved").contains(e.action()))throw new IllegalArgumentException("invalid notification action");
        OperationalNotification.safe(e.component(),64);OperationalNotification.safe(e.resourceKey(),512);
        String title="运维告警 "+e.level()+" "+e.ruleId()+" "+e.action();
        String message="event_id="+e.id()+"; rule_id="+e.ruleId()+"; component="+e.component()+"; resource="+e.resourceKey()
                +"; level="+e.level()+"; action="+e.action()+"; event_time="+e.at()+"; observed="+e.observedValue()+"; threshold="+e.threshold();
        return new Payload(e.ruleId(),title,message);
    }
}
