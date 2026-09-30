package com.lianxibu.site.service.settings;
import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
class OperationalRulePolicyTest {
    @Test void completeRulesHaveUnitsButLegacyIsNotExecutable() {
        for (var rule : OperationalFixtures.complete()) assertEquals(rule.ruleId(), OperationalRulePolicy.compile(rule).ruleId());
        var old = new SettingsService.OperationalRule("OPS-04", "15", "10", "15", null, false);
        assertFalse(OperationalRulePolicy.ready(old));
        assertThrows(IllegalArgumentException.class, () -> OperationalRulePolicy.compile(old));
    }
    @Test void unknownKeysMissingParametersUnitsAndUnsafeHysteresisAreRejected() {
        var rule = OperationalFixtures.complete().get(3);
        for (Map<String,String> map : java.util.List.of(Map.<String,String>of(), changed(rule, "unexpected", "1"),
                changed(rule, "warning_hold_seconds", "0.5"), changed(rule, "protect_free_percent", "10"),
                changed(rule, "protect_free_percent", "-1"), changed(rule, "sample_interval_seconds", "0"),
                changed(rule, "maximum_silence_seconds", "7201"), changed(rule, "owner_confirmation_required", "0"))) {
            assertThrows(IllegalArgumentException.class, () -> OperationalRulePolicy.compile(with(rule, map)));
        }
        assertThrows(IllegalArgumentException.class, () -> OperationalRulePolicy.compile(new SettingsService.OperationalRule(
                "OPS-04", "8", "10", "15", null, false, rule.ruleParameters())));
        assertThrows(IllegalArgumentException.class, () -> OperationalRulePolicy.compile(new SettingsService.OperationalRule(
                "unknown", "15", "10", "15", null, false, rule.ruleParameters())));
    }
    static Map<String,String> changed(SettingsService.OperationalRule rule, String key, String value) {
        var map = new HashMap<>(rule.ruleParameters()); map.put(key, value); return map;
    }
    static SettingsService.OperationalRule with(SettingsService.OperationalRule rule, Map<String,String> map) {
        return new SettingsService.OperationalRule(rule.ruleId(), rule.warningThreshold(), rule.criticalThreshold(),
                rule.recoveryThreshold(), rule.notificationSecretRef(), rule.notificationConfigured(), map);
    }
}
