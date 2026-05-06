package com.achetronic.keycloak.dcrlifecycle;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the cleanup-strategy logic of {@link DcrOrphanCleanupTask}.
 * <p>
 * Strategies A and B are exercised on synthetic groups without standing up
 * Keycloak. Only the deterministic decision logic (which UUIDs to delete) is
 * verified here; persistence is covered by integration tests.
 */
class DcrOrphanCleanupTaskTest {

    private static final long NOW = 1_000_000_000_000L;
    private static final long ONE_DAY_MS = 24L * 60L * 60L * 1000L;

    /**
     * Helper: build a mock DCR client with the given id and used_at timestamp.
     */
    private ClientModel mockClient(String id, long usedAt) {
        ClientModel client = Mockito.mock(ClientModel.class);
        when(client.getId()).thenReturn(id);
        when(client.getAttribute(DcrLifecycleEventListenerProvider.ATTR_USED_AT))
                .thenReturn(String.valueOf(usedAt));
        return client;
    }

    /**
     * Helper: build a mock DCR client representing a "pure orphan" (no linked user).
     */
    private ClientModel mockOrphanClient(String id, long createdAt) {
        ClientModel client = Mockito.mock(ClientModel.class);
        when(client.getId()).thenReturn(id);
        when(client.getAttribute(DcrLifecycleEventListenerProvider.ATTR_LINKED_USER_ID))
                .thenReturn(null);
        when(client.getAttribute(DcrLifecycleEventListenerProvider.ATTR_CREATED_AT))
                .thenReturn(String.valueOf(createdAt));
        return client;
    }

    /**
     * Helper: build a mock DCR client linked to a user (NOT an orphan).
     */
    private ClientModel mockLinkedClient(String id, String userId, long createdAt) {
        ClientModel client = Mockito.mock(ClientModel.class);
        when(client.getId()).thenReturn(id);
        when(client.getAttribute(DcrLifecycleEventListenerProvider.ATTR_LINKED_USER_ID))
                .thenReturn(userId);
        when(client.getAttribute(DcrLifecycleEventListenerProvider.ATTR_CREATED_AT))
                .thenReturn(String.valueOf(createdAt));
        return client;
    }

    private DcrOrphanCleanupTask taskWith(DcrLifecycleConfig config) {
        return new DcrOrphanCleanupTask(config);
    }

    @Test
    void strategyA_lonelyGroup_neverDeletes() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.A, 30, false, 24, 60);
        DcrOrphanCleanupTask task = taskWith(cfg);

        List<ClientModel> group = List.of(mockClient("only", NOW - 10 * ONE_DAY_MS));
        List<String> toDelete = task.applyStrategyA(group);

        assertTrue(toDelete.isEmpty(), "Lonely client must never be deleted under strategy A");
    }

    @Test
    void strategyA_groupOfThree_keepsMostRecent() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.A, 30, false, 24, 60);
        DcrOrphanCleanupTask task = taskWith(cfg);

        ClientModel oldest = mockClient("oldest", NOW - 5 * ONE_DAY_MS);
        ClientModel middle = mockClient("middle", NOW - 2 * ONE_DAY_MS);
        ClientModel newest = mockClient("newest", NOW - ONE_DAY_MS / 2);
        List<ClientModel> group = new ArrayList<>(Arrays.asList(oldest, middle, newest));

        List<String> toDelete = task.applyStrategyA(group);

        assertEquals(2, toDelete.size());
        assertTrue(toDelete.contains("oldest"));
        assertTrue(toDelete.contains("middle"));
        assertFalse(toDelete.contains("newest"), "Most recently used client must be kept");
    }

    @Test
    void strategyB_groupOfTwo_onlyInactiveOnesDeleted() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.B, 30, false, 24, 60);
        DcrOrphanCleanupTask task = taskWith(cfg);

        ClientModel inactive = mockClient("inactive", NOW - 60 * ONE_DAY_MS); // 60 days
        ClientModel active = mockClient("active", NOW - 5 * ONE_DAY_MS);    // 5 days
        List<ClientModel> group = new ArrayList<>(Arrays.asList(inactive, active));

        List<String> toDelete = task.applyStrategyB(group, NOW);

        assertEquals(1, toDelete.size());
        assertTrue(toDelete.contains("inactive"));
        assertFalse(toDelete.contains("active"));
    }

    @Test
    void strategyB_lonelyInactive_keptWhenLonelyFlagFalse() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.B, 30, false, 24, 60);
        DcrOrphanCleanupTask task = taskWith(cfg);

        List<ClientModel> group = List.of(mockClient("lonely", NOW - 100 * ONE_DAY_MS));

        List<String> toDelete = task.applyStrategyB(group, NOW);

        assertTrue(toDelete.isEmpty(),
                "Lonely inactive client must NOT be deleted when DELETE_LONELY_INACTIVE=false");
    }

    @Test
    void strategyB_lonelyInactive_deletedWhenLonelyFlagTrue() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.B, 30, true, 24, 60);
        DcrOrphanCleanupTask task = taskWith(cfg);

        List<ClientModel> group = List.of(mockClient("lonely", NOW - 100 * ONE_DAY_MS));

        List<String> toDelete = task.applyStrategyB(group, NOW);

        assertEquals(1, toDelete.size());
        assertTrue(toDelete.contains("lonely"));
    }

    @Test
    void strategyB_lonelyActive_keptInBothFlagModes() {
        // Even when DELETE_LONELY_INACTIVE=true, an *active* lonely client must
        // not be deleted (the flag only authorizes deletion when truly inactive).
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.B, 30, true, 24, 60);
        DcrOrphanCleanupTask task = taskWith(cfg);

        List<ClientModel> group = List.of(mockClient("lonely-active", NOW - 5 * ONE_DAY_MS));

        List<String> toDelete = task.applyStrategyB(group, NOW);

        assertTrue(toDelete.isEmpty(), "Active lonely client must always be kept");
    }

    // -------------------------------------------------------------------------
    // collectPureOrphans (Phase 2 - Pass 1)
    // -------------------------------------------------------------------------

    @Test
    void collectPureOrphans_deletesUnlinkedClientsOlderThanGracePeriod() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.A, 30, false, 24, 60);
        DcrOrphanCleanupTask task = taskWith(cfg);

        long gracePeriodMs = cfg.getGracePeriodMs();
        ClientModel oldOrphan = mockOrphanClient("old-orphan", NOW - gracePeriodMs - ONE_DAY_MS);
        ClientModel veryOldOrphan = mockOrphanClient("very-old-orphan", NOW - gracePeriodMs - 30 * ONE_DAY_MS);

        List<String> toDelete = task.collectPureOrphans(List.of(oldOrphan, veryOldOrphan), NOW);

        assertEquals(2, toDelete.size());
        assertTrue(toDelete.contains("old-orphan"));
        assertTrue(toDelete.contains("very-old-orphan"));
    }

    @Test
    void collectPureOrphans_keepsClientsWithinGracePeriod() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.A, 30, false, 24, 60);
        DcrOrphanCleanupTask task = taskWith(cfg);

        // Created just 1 hour ago: must NOT be deleted (grace = 24h)
        ClientModel recent = mockOrphanClient("recent-orphan", NOW - 60 * 60 * 1000L);

        List<String> toDelete = task.collectPureOrphans(List.of(recent), NOW);

        assertTrue(toDelete.isEmpty(), "Orphan within grace period must NOT be deleted");
    }

    @Test
    void collectPureOrphans_ignoresLinkedClients() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.A, 30, false, 24, 60);
        DcrOrphanCleanupTask task = taskWith(cfg);

        // Linked client even older than the grace period: not an orphan, must be ignored
        ClientModel linked = mockLinkedClient("linked", "user-1", NOW - 100 * ONE_DAY_MS);

        List<String> toDelete = task.collectPureOrphans(List.of(linked), NOW);

        assertTrue(toDelete.isEmpty(), "Linked clients are not orphans and must be ignored here");
    }

    @Test
    void collectPureOrphans_handlesCorruptedTimestamp() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.A, 30, false, 24, 60);
        DcrOrphanCleanupTask task = taskWith(cfg);

        ClientModel corrupted = Mockito.mock(ClientModel.class);
        when(corrupted.getId()).thenReturn("corrupted");
        when(corrupted.getAttribute(DcrLifecycleEventListenerProvider.ATTR_LINKED_USER_ID)).thenReturn(null);
        when(corrupted.getAttribute(DcrLifecycleEventListenerProvider.ATTR_CREATED_AT)).thenReturn("not-a-number");

        List<String> toDelete = task.collectPureOrphans(List.of(corrupted), NOW);

        assertTrue(toDelete.isEmpty(), "Corrupted timestamp must default to safe (no deletion)");
    }

    @Test
    void collectPureOrphans_mixedSet() {
        DcrLifecycleConfig cfg = new DcrLifecycleConfig(
                DcrLifecycleConfig.Strategy.A, 30, false, 24, 60);
        DcrOrphanCleanupTask task = taskWith(cfg);

        long gracePeriodMs = cfg.getGracePeriodMs();
        ClientModel oldOrphan = mockOrphanClient("old-orphan", NOW - gracePeriodMs - ONE_DAY_MS);
        ClientModel recentOrphan = mockOrphanClient("recent-orphan", NOW - 60 * 60 * 1000L);
        ClientModel oldLinked = mockLinkedClient("old-linked", "user-1", NOW - 100 * ONE_DAY_MS);

        List<String> toDelete = task.collectPureOrphans(
                List.of(oldOrphan, recentOrphan, oldLinked), NOW);

        assertEquals(1, toDelete.size());
        assertTrue(toDelete.contains("old-orphan"));
    }
}
