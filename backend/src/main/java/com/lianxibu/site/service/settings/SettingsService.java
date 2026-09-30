package com.lianxibu.site.service.settings;

import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.List;
import java.util.Map;

/** Owner preferences port; fixed schedule/model fields are supplied by the HTTP projection. */
public interface SettingsService {
    record OperationalRule(String ruleId, String warningThreshold, String criticalThreshold,
                           String recoveryThreshold, String notificationSecretRef,
                           boolean notificationConfigured, Map<String, String> ruleParameters) {
        public OperationalRule {
            if (ruleParameters != null) ruleParameters = Map.copyOf(ruleParameters);
        }
        public OperationalRule(String ruleId, String warningThreshold, String criticalThreshold,
                               String recoveryThreshold, String notificationSecretRef, boolean notificationConfigured) {
            this(ruleId, warningThreshold, criticalThreshold, recoveryThreshold, notificationSecretRef,
                    notificationConfigured, null);
        }
    }
    record Preferences(long revision, List<String> followKeywords, List<String> blockedKeywords,
                       boolean aiPaused, String defaultPackageId, List<OperationalRule> operationalLimits) { }
    record PreferencesWrite(long expectedRevision, List<String> followKeywords, List<String> blockedKeywords,
                            boolean aiPaused, String defaultPackageId, List<OperationalRule> operationalLimits,
                            String operationalChangeReason) {
        public PreferencesWrite(long expectedRevision, List<String> followKeywords, List<String> blockedKeywords,
                                boolean aiPaused, String defaultPackageId, List<OperationalRule> operationalLimits) {
            this(expectedRevision, followKeywords, blockedKeywords, aiPaused, defaultPackageId, operationalLimits, null);
        }
    }

    Preferences get(Actor actor);
    Preferences write(Actor actor, PreferencesWrite command,
                      String idempotencyKey, String csrfToken, String origin);
}
