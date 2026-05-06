package com.achetronic.keycloak.dcrlifecycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DcrLifecycleConfig}.
 * <p>
 * Note: tests rely on directly constructing the config to avoid mutating the
 * JVM environment. {@code fromEnv()} is exercised via spot checks that do not
 * depend on env vars being set.
 */
class DcrLifecycleConfigTest {

    @AfterEach
    void clearProps() {
        // Nothing to clean up at the moment
    }

    @Test
    void testDefaultsViaConstructor() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.A, 30, false, 24, 60);
        assertEquals(DcrLifecycleConfig.Strategy.A, cfg.getStrategy());
        assertEquals(30, cfg.getInactivityDays());
        assertFalse(cfg.isDeleteLonelyInactive());
        assertEquals(24, cfg.getGracePeriodHours());
        assertEquals(60, cfg.getCleanupIntervalMinutes());
    }

    @Test
    void testGracePeriodMsConversion() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.A, 30, false, 24, 60);
        assertEquals(24L * 60L * 60L * 1000L, cfg.getGracePeriodMs());
    }

    @Test
    void testInactivityMsConversion() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.B, 30, true, 24, 60);
        assertEquals(30L * 24L * 60L * 60L * 1000L, cfg.getInactivityMs());
    }

    @Test
    void testCleanupIntervalMsConversion() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.A, 30, false, 24, 5);
        assertEquals(5L * 60L * 1000L, cfg.getCleanupIntervalMs());
    }

    @Test
    void testStrategyB() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.B, 15, true, 12, 30);
        assertEquals(DcrLifecycleConfig.Strategy.B, cfg.getStrategy());
        assertTrue(cfg.isDeleteLonelyInactive());
        assertEquals(15, cfg.getInactivityDays());
    }

    @Test
    void testFromEnvFallsBackToDefaultsWhenNoEnvVars() {
        // When no env vars are set, fromEnv() must produce safe defaults.
        DcrLifecycleConfig cfg = DcrLifecycleConfig.fromEnv();
        // We cannot assume env vars are unset on the test runner, but we can
        // assert that the values are well-formed and within sane ranges.
        assertTrue(cfg.getInactivityDays() >= 0);
        assertTrue(cfg.getGracePeriodHours() >= 0);
        assertTrue(cfg.getCleanupIntervalMinutes() > 0);
        assertTrue(cfg.getStrategy() == DcrLifecycleConfig.Strategy.A
                || cfg.getStrategy() == DcrLifecycleConfig.Strategy.B);
    }
}
