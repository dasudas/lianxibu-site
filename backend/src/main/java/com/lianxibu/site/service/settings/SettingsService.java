package com.lianxibu.site.service.settings;

import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.List;

/** Owner preferences port; fixed schedule/model fields are supplied by the HTTP projection. */
public interface SettingsService {
    record OperationalRule(String ruleId, String warningThreshold, String criticalThreshold,
                           String recoveryThreshold, String notificationSecretRef,
                           boolean notificationConfigured) { }
    record Preferences(long revision, List<String> followKeywords, List<String> blockedKeywords,
                       boolean aiPaused, String defaultPackageId, List<OperationalRule> operationalLimits) { }
    record PreferencesWrite(long expectedRevision, List<String> followKeywords, List<String> blockedKeywords,
                            boolean aiPaused, String defaultPackageId, List<OperationalRule> operationalLimits) { }

    Preferences get(Actor actor);
    Preferences write(Actor actor, PreferencesWrite command,
                      String idempotencyKey, String csrfToken, String origin);
}
