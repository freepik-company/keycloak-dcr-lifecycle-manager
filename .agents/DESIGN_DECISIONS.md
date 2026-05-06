# DCR Client Linking and Cleanup Strategy in Keycloak

## Problem
Dynamic Client Registration (DCR), used by platforms and MCPs (such as ChatGPT and Claude), generates a massive volume of clients. Many of these clients become orphans once the user session ends. Additionally, manually created clients coexist in the realm with DCR clients, so the cleanup must be highly selective.

## Data Model: 5 Attributes per DCR Client

| Attribute        | When written              | Who writes it                  | Purpose |
|------------------|---------------------------|--------------------------------|---------|
| `is_dcr_client`  | On DCR registration       | Phase 1 (`CLIENT_REGISTER`)    | Authoritative flag. If not `"true"`, no SPI component touches the client. |
| `created_at`     | On DCR registration       | Phase 1 (`CLIENT_REGISTER`)    | Epoch ms timestamp. Used by Phase 2 to detect pure orphans. |
| `fingerprint`    | On DCR registration       | Phase 1 (`CLIENT_REGISTER`)    | Deterministic SHA-256 of `redirect_uris` + client name. Groups equivalent clients (e.g., all Claude clients share the same fingerprint). |
| `linked_user_id` | On every successful LOGIN | Phase 1 (`LOGIN`)              | Keycloak user UUID that has used the client. |
| `used_at`        | On every successful LOGIN | Phase 1 (`LOGIN`)              | Epoch ms timestamp of the most recent login. Allows Phase 2 to decide the "winner" between duplicates. |

The `is_dcr_client` flag is the source of truth. The other attributes use generic names (without prefix) for consistency with Keycloak's natural model.

## Phase 1: Hot-path Tagging (Event Listener)

### `CLIENT_REGISTER`
- Intercepts the standard OIDC event (not the `AdminEvent`), guaranteeing that **manually created clients are never tagged**.
- Atomically writes **3 attributes**: `is_dcr_client`, `created_at`, `fingerprint`.

### `LOGIN`
- Instant filter: if the client is not tagged with `is_dcr_client="true"`, return early.
- Writes **2 attributes**: `linked_user_id` and `used_at`.
- **No other clients are scanned and nothing is deleted at this point.** The hot path stays at constant O(1).
- Rationale: performing synchronous cleanup during login introduced two risks:
    1. Unacceptable latency in large realms (scanning thousands of clients).
    2. Race conditions with concurrent logins of the same user across different clients (mutual deletion).

## Phase 2: Garbage Collector (Scheduled Task with HA)

A scheduled task runs every `DCR_LIFECYCLE_CLEANUP_INTERVAL_MINUTES` minutes (default 60) and executes under a **distributed lock** via `ClusterProvider.executeIfNotExecuted`. In an N-node cluster, only one node runs it at a time.

The task scans every client where `is_dcr_client="true"` and applies two passes:

### Pass 1: Pure Orphans
A client whose `linked_user_id == null` and `now - created_at > GRACE_PERIOD_HOURS` is deleted.

### Pass 2: Linked Duplicates
Linked clients are grouped by `(linked_user_id, fingerprint)` and the **configured strategy** is applied:

#### Strategy A — "Last wins" (default)
- If the group contains more than one client: keep the most recent `used_at`, delete the rest.
- If the group contains exactly one client: **never** delete.

**When to choose A:** when DCR clients tend to be ephemeral (platforms regenerate a new one every time the user reconnects).

#### Strategy B — "Inactivity grace"
- A client whose `now - used_at > INACTIVITY_DAYS` becomes a deletion candidate.
- If the client is the only one in its group, it is only deleted when `DELETE_LONELY_INACTIVE=true`.

**When to choose B:** when DCR clients remain stable for months and a single user may legitimately have several active devices.

## Key Decisions and Why

### Why remove cleanup from the LOGIN path?
Initially the cleanup happened synchronously during LOGIN. Two problems were detected:
1. **Latency:** in realms with thousands of clients, the scan added hundreds of ms to every login.
2. **Race conditions:** two concurrent logins of the same user on different Claude clients deleted each other.

The solution was to move all deletion logic to Phase 2, leaving LOGIN with just the `linked_user_id` and `used_at` updates. Phase 2 has all the information it needs thanks to the 5-attribute model.

### Why `is_dcr_client` if we already have `created_at`?
For semantic consistency: the flag explicitly declares the nature of the client. Filtering by `created_at != null` would technically work but loses clarity. The cost is one extra attribute, irrelevant in practice.

### Why configure via environment variables instead of Keycloak's `Config.Scope`?
Simpler to inject in containerized environments (Docker, Kubernetes) and consistent with standard 12-factor deployment practices.

---

Both phases are implemented in `DcrLifecycleEventListenerProvider`, `DcrOrphanCleanupTask`, and `DcrLifecycleEventListenerProviderFactory`, with configuration encapsulated in `DcrLifecycleConfig`.
