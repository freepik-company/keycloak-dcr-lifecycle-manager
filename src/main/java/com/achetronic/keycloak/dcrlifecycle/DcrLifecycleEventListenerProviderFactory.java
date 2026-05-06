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
 * Also responsible for scheduling the orphan cleanup timer task on startup.
 */
public class DcrLifecycleEventListenerProviderFactory implements EventListenerProviderFactory {

    private static final Logger log = Logger.getLogger(DcrLifecycleEventListenerProviderFactory.class);

    /** Keycloak provider id used to register this Event Listener in the Realm settings. */
    public static final String PROVIDER_ID = "dcr-lifecycle-manager";

    /** Name of the scheduled cleanup task registered into Keycloak's TimerProvider. */
    private static final String CLEANUP_TASK_NAME = "dcr-orphan-cleanup-task";

    /** Frequency of the scheduled cleanup task (1 hour, in milliseconds). */
    private static final long CLEANUP_INTERVAL_MS = 60 * 60 * 1000L;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new DcrLifecycleEventListenerProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
        // No initialization required
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Register the scheduled task during the post-initialization phase.
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            TimerProvider timer = session.getProvider(TimerProvider.class);
            if (timer != null) {
                timer.scheduleTask(new DcrOrphanCleanupTask(), CLEANUP_INTERVAL_MS, CLEANUP_TASK_NAME);
                log.infof("Scheduled orphan DCR cleanup task '%s' every %d ms.", CLEANUP_TASK_NAME, CLEANUP_INTERVAL_MS);
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
