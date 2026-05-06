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
 * Also responsible for scheduling the Orphan Cleanup Timer Task on startup.
 */
public class DcrLifecycleEventListenerProviderFactory implements EventListenerProviderFactory {

    private static final Logger log = Logger.getLogger(DcrLifecycleEventListenerProviderFactory.class);
    public static final String PROVIDER_ID = "dcr-lifecycle-manager";

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new DcrLifecycleEventListenerProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
        // Initialization logic here
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Registramos la tarea programada en la fase de Post-Inicialización
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            TimerProvider timer = session.getProvider(TimerProvider.class);
            if (timer != null) {
                // Programamos la limpieza cada hora (3,600,000 ms)
                long intervalMs = 60 * 60 * 1000L;
                timer.scheduleTask(new DcrOrphanCleanupTask(), intervalMs, "dcr-orphan-cleanup-task");
                log.info("Tarea de limpieza de clientes DCR huérfanos programada (cada hora).");
            } else {
                log.warn("TimerProvider no encontrado. No se ha programado la limpieza de huérfanos DCR.");
            }
        });
    }

    @Override
    public void close() {
        // Cleanup logic here
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}