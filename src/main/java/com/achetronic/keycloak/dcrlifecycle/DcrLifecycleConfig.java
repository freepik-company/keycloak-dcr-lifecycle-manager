package com.achetronic.keycloak.dcrlifecycle;

import org.jboss.logging.Logger;

/**
 * Immutable configuration for the DCR Lifecycle Manager SPI.
 * <p>
 * Values are read once at startup from environment variables and propagated to
 * runtime components via constructor injection, ensuring deterministic behavior
 * for the lifetime of the Keycloak instance.
 */
public final class DcrLifecycleConfig {

    private static final Logger log = Logger.getLogger(DcrLifecycleConfig.class);

    /** Cleanup philosophy for duplicated linked clients. */
    public enum Strategy {
        /** "Last wins": keep only the most recently used client per (user, fingerprint) group. */
        A,
        /** "Inactivity grace": delete linked clients not used for INACTIVITY_DAYS. */
        B
    }

    public static final String ENV_STRATEGY = "DCR_LIFECYCLE_STRATEGY";
    public static final String ENV_INACTIVITY_DAYS = "DCR_LIFECYCLE_INACTIVITY_DAYS";
    public static final String ENV_DELETE_LONELY_INACTIVE = "DCR_LIFECYCLE_DELETE_LONELY_INACTIVE";
    public static final String ENV_GRACE_PERIOD_HOURS = "DCR_LIFECYCLE_GRACE_PERIOD_HOURS";
    public static final String ENV_CLEANUP_INTERVAL_MINUTES = "DCR_LIFECYCLE_CLEANUP_INTERVAL_MINUTES";

    private final Strategy strategy;
    private final int inactivityDays;
    private final boolean deleteLonelyInactive;
    private final int gracePeriodHours;
    private final int cleanupIntervalMinutes;

    public DcrLifecycleConfig(Strategy strategy,
                              int inactivityDays,
                              boolean deleteLonelyInactive,
                              int gracePeriodHours,
                              int cleanupIntervalMinutes) {
        this.strategy = strategy;
        this.inactivityDays = inactivityDays;
        this.deleteLonelyInactive = deleteLonelyInactive;
        this.gracePeriodHours = gracePeriodHours;
        this.cleanupIntervalMinutes = cleanupIntervalMinutes;
    }

    /**
     * Builds a configuration object reading values from environment variables.
     * Falls back to safe defaults when a variable is missing or malformed.
     */
    public static DcrLifecycleConfig fromEnv() {
        Strategy strategy = parseStrategy(System.getenv(ENV_STRATEGY));
        int inactivityDays = parseInt(System.getenv(ENV_INACTIVITY_DAYS), 30, ENV_INACTIVITY_DAYS);
        boolean deleteLonelyInactive = parseBool(System.getenv(ENV_DELETE_LONELY_INACTIVE), false);
        int gracePeriodHours = parseInt(System.getenv(ENV_GRACE_PERIOD_HOURS), 24, ENV_GRACE_PERIOD_HOURS);
        int cleanupIntervalMinutes = parseInt(System.getenv(ENV_CLEANUP_INTERVAL_MINUTES), 60, ENV_CLEANUP_INTERVAL_MINUTES);

        DcrLifecycleConfig cfg = new DcrLifecycleConfig(strategy, inactivityDays, deleteLonelyInactive,
                gracePeriodHours, cleanupIntervalMinutes);
        log.infof("DCR Lifecycle config loaded: %s", cfg);
        return cfg;
    }

    private static Strategy parseStrategy(String value) {
        if (value == null || value.isBlank()) {
            return Strategy.A;
        }
        try {
            return Strategy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warnf("Invalid value for %s: '%s'. Falling back to A.", ENV_STRATEGY, value);
            return Strategy.A;
        }
    }

    private static int parseInt(String value, int defaultValue, String varName) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warnf("Invalid integer for %s: '%s'. Falling back to %d.", varName, value, defaultValue);
            return defaultValue;
        }
    }

    private static boolean parseBool(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public int getInactivityDays() {
        return inactivityDays;
    }

    public boolean isDeleteLonelyInactive() {
        return deleteLonelyInactive;
    }

    public int getGracePeriodHours() {
        return gracePeriodHours;
    }

    public int getCleanupIntervalMinutes() {
        return cleanupIntervalMinutes;
    }

    public long getGracePeriodMs() {
        return gracePeriodHours * 60L * 60L * 1000L;
    }

    public long getInactivityMs() {
        return inactivityDays * 24L * 60L * 60L * 1000L;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMinutes * 60L * 1000L;
    }

    @Override
    public String toString() {
        return "DcrLifecycleConfig{" +
                "strategy=" + strategy +
                ", inactivityDays=" + inactivityDays +
                ", deleteLonelyInactive=" + deleteLonelyInactive +
                ", gracePeriodHours=" + gracePeriodHours +
                ", cleanupIntervalMinutes=" + cleanupIntervalMinutes +
                '}';
    }
}
