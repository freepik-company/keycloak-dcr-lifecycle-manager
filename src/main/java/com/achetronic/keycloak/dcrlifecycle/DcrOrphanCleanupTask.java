package com.achetronic.keycloak.dcrlifecycle;

import org.jboss.logging.Logger;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.cluster.ExecutionResult;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.timer.ScheduledTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * A Keycloak Scheduled Task that cleans up orphaned DCR clients.
 * Runs periodically and ensures HA compatibility using Keycloak's ClusterProvider
 * distributed lock system. Looks for DCR clients created more than the grace period
 * ago that never received a user login.
 */
public class DcrOrphanCleanupTask implements ScheduledTask {

    private static final Logger log = Logger.getLogger(DcrOrphanCleanupTask.class);

    /** Grace period before deleting an orphan DCR client (24 hours). */
    private static final long GRACE_PERIOD_MS = 24 * 60 * 60 * 1000L;

    /** Distributed lock timeout to ensure only one cluster node runs the cleanup at a time. */
    private static final int LOCK_TIMEOUT_SECONDS = 30;

    /** Distributed lock key shared by all cluster nodes. */
    private static final String TASK_KEY = "dcr-orphan-cleanup-lock";

    /**
     * Entry point for the scheduled task.
     * Uses ClusterProvider to obtain a distributed lock before executing the cleanup,
     * preventing multiple cluster nodes from running it simultaneously.
     *
     * @param session The Keycloak Session.
     */
    @Override
    public void run(KeycloakSession session) {
        ClusterProvider clusterProvider = session.getProvider(ClusterProvider.class);
        if (clusterProvider == null) {
            log.warn("ClusterProvider not found, executing cleanup in local mode (no-HA)");
            doCleanup(session);
            return;
        }

        Callable<Void> task = () -> {
            doCleanup(session);
            return null;
        };

        try {
            ExecutionResult<Void> result = clusterProvider.executeIfNotExecuted(TASK_KEY, LOCK_TIMEOUT_SECONDS, task);
            if (result.isExecuted()) {
                log.debug("Orphan DCR cleanup executed on this node.");
            } else {
                log.debug("Orphan DCR cleanup skipped on this node (already running on another node).");
            }
        } catch (Exception e) {
            log.error("Failed to execute orphan DCR cleanup task", e);
        }
    }

    /**
     * Performs the actual cleanup logic across all realms.
     * Materializes the list of UUIDs to delete BEFORE removing them, to avoid
     * concurrent modification while iterating the client stream. Tagged DCR clients
     * with no linked user older than the grace period are deleted.
     *
     * @param session The Keycloak Session.
     */
    private void doCleanup(KeycloakSession session) {
        long now = System.currentTimeMillis();

        session.realms().getRealmsStream().forEach(realm -> {
            List<String> clientsToDelete = collectOrphanClientIds(session, realm, now);

            for (String uuid : clientsToDelete) {
                try {
                    ClientModel client = session.clients().getClientById(realm, uuid);
                    if (client == null) {
                        continue;
                    }
                    log.infof("Deleting orphan DCR client %s in realm %s", client.getClientId(), realm.getName());
                    session.clients().removeClient(realm, uuid);
                } catch (Exception e) {
                    log.errorf(e, "Failed to delete orphan DCR client %s in realm %s", uuid, realm.getName());
                }
            }
        });
    }

    /**
     * Collects the UUIDs of DCR clients that should be considered orphans:
     * <ul>
     *   <li>Have a {@code dcr_created_at} attribute (DCR-tagged).</li>
     *   <li>Do NOT have a {@code linked_user_id} attribute (never logged in).</li>
     *   <li>Were created more than {@link #GRACE_PERIOD_MS} ago.</li>
     * </ul>
     *
     * @param session The Keycloak Session.
     * @param realm   The Realm to scan.
     * @param now     Current time in millis (passed in to keep consistency across the iteration).
     * @return The list of client UUIDs to delete.
     */
    private List<String> collectOrphanClientIds(KeycloakSession session, RealmModel realm, long now) {
        List<String> ids = new ArrayList<>();
        session.clients().getClientsStream(realm)
                .filter(c -> c.getAttribute(DcrLifecycleEventListenerProvider.ATTR_DCR_CREATED_AT) != null)
                .filter(c -> c.getAttribute(DcrLifecycleEventListenerProvider.ATTR_LINKED_USER_ID) == null)
                .filter(c -> isOlderThanGracePeriod(c, now))
                .forEach(c -> ids.add(c.getId()));
        return ids;
    }

    /**
     * Returns true if the client's {@code dcr_created_at} timestamp is older than
     * the grace period. If the attribute cannot be parsed, returns false to avoid
     * deleting clients with corrupted timestamp data.
     */
    private boolean isOlderThanGracePeriod(ClientModel client, long now) {
        String createdAtStr = client.getAttribute(DcrLifecycleEventListenerProvider.ATTR_DCR_CREATED_AT);
        try {
            long createdAt = Long.parseLong(createdAtStr);
            return (now - createdAt) > GRACE_PERIOD_MS;
        } catch (NumberFormatException e) {
            // Corrupted or unexpected timestamp: do not delete to be safe.
            return false;
        }
    }
}
