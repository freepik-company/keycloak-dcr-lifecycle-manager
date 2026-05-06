package com.achetronic.keycloak.dcrlifecycle;

import org.jboss.logging.Logger;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.cluster.ExecutionResult;
import org.keycloak.models.KeycloakSession;
import org.keycloak.timer.ScheduledTask;

import java.util.concurrent.Callable;

/**
 * A Keycloak Scheduled Task that cleans up orphaned DCR clients.
 * This runs periodically and ensures HA compatibility using Keycloak's ClusterProvider
 * distributed lock system. It looks for DCR clients created more than 24h ago
 * that never received a user login.
 */
public class DcrOrphanCleanupTask implements ScheduledTask {
    private static final Logger log = Logger.getLogger(DcrOrphanCleanupTask.class);
    
    // 24 horas en milisegundos
    private static final long GRACE_PERIOD_MS = 24 * 60 * 60 * 1000L; 
    private static final String TASK_KEY = "dcr-orphan-cleanup-lock";

    /**
     * Entry point for the scheduled task.
     * Uses ClusterProvider to obtain a distributed lock before executing the cleanup
     * to prevent multiple cluster nodes from running it simultaneously.
     *
     * @param session The Keycloak Session.
     */
    @Override
    public void run(KeycloakSession session) {
        ClusterProvider clusterProvider = session.getProvider(ClusterProvider.class);
        if (clusterProvider == null) {
            log.warn("ClusterProvider no encontrado, ejecutando limpieza en modo local (no-HA)");
            doCleanup(session);
            return;
        }

        // Usamos un candado distribuido de 30 segundos de timeout
        // Asegura que en un clúster de 5 nodos, solo 1 ejecute la tarea
        Callable<Void> task = () -> {
            doCleanup(session);
            return null;
        };

        ExecutionResult<Void> result = clusterProvider.executeIfNotExecuted(TASK_KEY, 30, task);

        if (result.isExecuted()) {
            log.debug("Limpieza de huérfanos DCR ejecutada en este nodo.");
        }
    }

    /**
     * Performs the actual cleanup logic across all realms.
     * Finds DCR clients that have the DCR tag but lack a linked user,
     * and deletes them if they are older than the grace period.
     *
     * @param session The Keycloak Session.
     */
    private void doCleanup(KeycloakSession session) {
        long now = System.currentTimeMillis();
        
        // Iteramos por todos los realms del servidor
        session.realms().getRealmsStream().forEach(realm -> {
            session.clients().getClientsStream(realm)
                // 1. Tiene la marca de haber nacido por DCR
                .filter(c -> c.getAttribute(DcrLifecycleEventListenerProvider.ATTR_DCR_CREATED_AT) != null)
                // 2. NUNCA se ha logado nadie con él (no tiene dueño vinculado)
                .filter(c -> c.getAttribute(DcrLifecycleEventListenerProvider.ATTR_LINKED_USER_ID) == null)
                // 3. Ya ha pasado el periodo de gracia de 24h
                .filter(c -> {
                    String createdAtStr = c.getAttribute(DcrLifecycleEventListenerProvider.ATTR_DCR_CREATED_AT);
                    try {
                        long createdAt = Long.parseLong(createdAtStr);
                        return (now - createdAt) > GRACE_PERIOD_MS;
                    } catch (Exception e) {
                        return false; // Ante la duda o dato corrupto, no borramos
                    }
                })
                .forEach(c -> {
                    log.infof("Borrando cliente DCR huérfano puro %s en el realm %s", c.getClientId(), realm.getName());
                    session.clients().removeClient(realm, c.getId());
                });
        });
    }
}