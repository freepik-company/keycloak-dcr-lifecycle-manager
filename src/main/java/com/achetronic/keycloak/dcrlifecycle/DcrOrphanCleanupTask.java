package com.achetronic.keycloak.dcrlifecycle;

import org.jboss.logging.Logger;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.cluster.ExecutionResult;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.timer.ScheduledTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Scheduled task that performs DCR client garbage collection.
 * <p>
 * Operates on tagged DCR clients ({@code is_dcr_client=true}) in two categories:
 * <ol>
 *   <li><b>Pure orphans</b>: clients with no {@code linked_user_id} older than the
 *       configured grace period are deleted.</li>
 *   <li><b>Linked duplicates</b>: clients grouped by {@code (linked_user_id, fingerprint)}
 *       are pruned according to the configured cleanup strategy:
 *       <ul>
 *         <li><b>Strategy A</b> ("last wins"): in groups with more than one client,
 *             keep the most recently used and delete the rest. Single-client groups
 *             are never deleted.</li>
 *         <li><b>Strategy B</b> ("inactivity grace"): delete clients whose
 *             {@code used_at} is older than {@code INACTIVITY_DAYS}; lonely
 *             clients are only deleted if {@code DELETE_LONELY_INACTIVE=true}.</li>
 *       </ul>
 *   </li>
 * </ol>
 * Uses Keycloak's {@link ClusterProvider} to acquire a distributed lock so that
 * only one node executes the cleanup at a time in HA deployments.
 */
public class DcrOrphanCleanupTask implements ScheduledTask {

    private static final Logger log = Logger.getLogger(DcrOrphanCleanupTask.class);

    /** Distributed lock timeout (seconds) to ensure only one cluster node runs the cleanup. */
    private static final int LOCK_TIMEOUT_SECONDS = 30;

    /** Distributed lock key shared by all cluster nodes. */
    private static final String TASK_KEY = "dcr-orphan-cleanup-lock";

    private final DcrLifecycleConfig config;

    public DcrOrphanCleanupTask(DcrLifecycleConfig config) {
        this.config = config;
    }

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
     * Performs the cleanup pass over every realm.
     * Materializes the candidate UUID list before deletion to avoid concurrent
     * modification while iterating client streams.
     */
    private void doCleanup(KeycloakSession session) {
        long now = System.currentTimeMillis();

        session.realms().getRealmsStream().forEach(realm -> {
            List<ClientModel> dcrClients = session.clients().getClientsStream(realm)
                    .filter(c -> "true".equals(c.getAttribute(DcrLifecycleEventListenerProvider.ATTR_IS_DCR_CLIENT)))
                    .collect(Collectors.toList());

            List<String> idsToDelete = new ArrayList<>();
            idsToDelete.addAll(collectPureOrphans(dcrClients, now));
            idsToDelete.addAll(collectLinkedDuplicates(dcrClients, now));

            for (String uuid : idsToDelete) {
                deleteSafely(session, realm, uuid);
            }
        });
    }

    /**
     * Returns the UUIDs of DCR clients that have never been linked to a user
     * and exceeded the grace period.
     */
    // Visibility: package-private for testing
    List<String> collectPureOrphans(List<ClientModel> dcrClients, long now) {
        long gracePeriodMs = config.getGracePeriodMs();
        return dcrClients.stream()
                .filter(c -> c.getAttribute(DcrLifecycleEventListenerProvider.ATTR_LINKED_USER_ID) == null)
                .filter(c -> isOlderThan(c, DcrLifecycleEventListenerProvider.ATTR_CREATED_AT, now, gracePeriodMs))
                .map(ClientModel::getId)
                .collect(Collectors.toList());
    }

    /**
     * Returns the UUIDs of DCR clients to delete from the linked-duplicate buckets,
     * applying the configured cleanup strategy.
     */
    private List<String> collectLinkedDuplicates(List<ClientModel> dcrClients, long now) {
        // Group all linked clients by (user, fingerprint)
        Map<String, List<ClientModel>> groups = new HashMap<>();
        for (ClientModel client : dcrClients) {
            String userId = client.getAttribute(DcrLifecycleEventListenerProvider.ATTR_LINKED_USER_ID);
            String fingerprint = client.getAttribute(DcrLifecycleEventListenerProvider.ATTR_FINGERPRINT);
            if (userId == null || fingerprint == null) {
                continue;
            }
            String key = userId + "|" + fingerprint;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(client);
        }

        List<String> ids = new ArrayList<>();
        for (List<ClientModel> group : groups.values()) {
            if (config.getStrategy() == DcrLifecycleConfig.Strategy.A) {
                ids.addAll(applyStrategyA(group));
            } else {
                ids.addAll(applyStrategyB(group, now));
            }
        }
        return ids;
    }

    /**
     * Strategy A: "last wins". For groups with more than one client, keeps the
     * one with the most recent {@code used_at} and deletes the rest. Lonely
     * clients are never deleted.
     */
    // Visibility: package-private for testing
    List<String> applyStrategyA(List<ClientModel> group) {
        if (group.size() <= 1) {
            return List.of();
        }
        // Sort descending by used_at; keep first, delete the rest.
        List<ClientModel> sorted = new ArrayList<>(group);
        sorted.sort(Comparator.comparingLong(this::getUsedAtSafe).reversed());
        return sorted.stream().skip(1).map(ClientModel::getId).collect(Collectors.toList());
    }

    /**
     * Strategy B: "inactivity grace". Deletes clients whose {@code used_at}
     * exceeds the configured inactivity window. Lonely groups (size 1) are only
     * pruned when {@code DELETE_LONELY_INACTIVE=true}.
     */
    // Visibility: package-private for testing
    List<String> applyStrategyB(List<ClientModel> group, long now) {
        long inactivityMs = config.getInactivityMs();
        boolean deleteLonely = config.isDeleteLonelyInactive();

        List<ClientModel> inactive = group.stream()
                .filter(c -> isOlderThan(c, DcrLifecycleEventListenerProvider.ATTR_USED_AT, now, inactivityMs))
                .collect(Collectors.toList());

        if (inactive.isEmpty()) {
            return List.of();
        }

        // Lonely group: only delete if explicitly allowed
        if (group.size() == 1 && !deleteLonely) {
            return List.of();
        }

        return inactive.stream().map(ClientModel::getId).collect(Collectors.toList());
    }

    /**
     * Returns true if the {@code used_at}/{@code created_at} attribute of the
     * client is older than the given window. Returns false on parsing errors
     * to be safe (do not delete on corrupted data).
     */
    private boolean isOlderThan(ClientModel client, String attribute, long now, long windowMs) {
        String raw = client.getAttribute(attribute);
        if (raw == null) {
            return false;
        }
        try {
            long ts = Long.parseLong(raw);
            return (now - ts) > windowMs;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Returns the {@code used_at} timestamp of the client; defaults to 0 when
     * absent or corrupted so that such clients sort to the end (oldest first).
     */
    private long getUsedAtSafe(ClientModel client) {
        String raw = client.getAttribute(DcrLifecycleEventListenerProvider.ATTR_USED_AT);
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Deletes a client by UUID, gracefully handling the case where another node
     * has already deleted it (race condition tolerance).
     */
    private void deleteSafely(KeycloakSession session, RealmModel realm, String uuid) {
        try {
            ClientModel client = session.clients().getClientById(realm, uuid);
            if (client == null) {
                return;
            }
            log.infof("Deleting DCR client %s in realm %s", client.getClientId(), realm.getName());
            session.clients().removeClient(realm, uuid);
        } catch (Exception e) {
            log.errorf(e, "Failed to delete DCR client %s in realm %s", uuid, realm.getName());
        }
    }
}
