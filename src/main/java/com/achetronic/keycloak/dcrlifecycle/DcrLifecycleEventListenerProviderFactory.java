package com.achetronic.keycloak.dcrlifecycle;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.timer.TimerProvider;

/**
 * Factory for creating instances of {@link DcrLifecycleEventListenerProvider}.
 * <p>
 * Loads the runtime configuration from environment variables on startup and
 * registers the periodic {@link DcrOrphanCleanupTask} with Keycloak's
 * {@link TimerProvider}.
 */
public class DcrLifecycleEventListenerProviderFactory implements EventListenerProviderFactory {

    private static final Logger log = Logger.getLogger(DcrLifecycleEventListenerProviderFactory.class);

    /** Keycloak provider id used to register this Event Listener in the Realm settings. */
    public static final String PROVIDER_ID = "dcr-lifecycle-manager";

    /** Name of the scheduled cleanup task registered into Keycloak's TimerProvider. */
    private static final String CLEANUP_TASK_NAME = "dcr-orphan-cleanup-task";

    private DcrLifecycleConfig config;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new DcrLifecycleEventListenerProvider(session);
    }

    @Override
    public void init(Config.Scope scope) {
        this.config = DcrLifecycleConfig.fromEnv();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        if (config == null) {
            // Defensive: should never happen since init() runs before postInit().
            config = DcrLifecycleConfig.fromEnv();
        }

        final DcrLifecycleConfig effectiveConfig = config;
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            TimerProvider timer = session.getProvider(TimerProvider.class);
            if (timer != null) {
                long intervalMs = effectiveConfig.getCleanupIntervalMs();
                timer.scheduleTask(new DcrOrphanCleanupTask(effectiveConfig), intervalMs, CLEANUP_TASK_NAME);
                log.infof("Scheduled orphan DCR cleanup task '%s' every %d minutes (strategy=%s).",
                        CLEANUP_TASK_NAME, effectiveConfig.getCleanupIntervalMinutes(), effectiveConfig.getStrategy());
            } else {
                log.warn("TimerProvider not found. Orphan DCR cleanup task will NOT run.");
            }
        });
    }

    @Override
    public void close() {
        // No resources to release
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
