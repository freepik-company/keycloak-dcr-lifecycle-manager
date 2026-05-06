# Keycloak DCR Lifecycle Manager

A Keycloak Event Listener SPI that elegantly manages the lifecycle of Dynamic Client Registration (DCR) clients.

## The Problem

Applications and MCPs (like ChatGPT, Claude, Zed, Cursor) use DCR to dynamically create a new Keycloak Client for each integration. Because the OAuth2 standard doesn't natively link a dynamically created client to the user who subsequently logs into it, this results in:

1. A massive explosion of orphaned clients in the Keycloak database.
2. Leftover clients from abandoned login attempts.
3. Multiple active clients for the same user if they integrate from different devices.

## The Solution

This SPI implements a complete garbage collection and linking system in two phases:

### Phase 1: Real-time Tagging & Marking (hot path)

- **Tagging on Creation:** Intercepts Keycloak's `CLIENT_REGISTER` event (which fires exclusively during standard OIDC DCR flows, ensuring manually created clients are safely ignored). It atomically writes three attributes: `is_dcr_client=true`, `created_at=<ms>` and `fingerprint=<sha256>`.
- **Marking on Login:** Intercepts standard `LOGIN` events. If the client is a tagged DCR client, it writes two attributes: `linked_user_id=<userId>` and `used_at=<ms>`. **No scans, no deletions** are performed during login: the hot path is just two `setAttribute` calls.

### Phase 2: Asynchronous Garbage Collection

- **Cluster-Aware:** A scheduled task (`TimerProvider` + `ClusterProvider`) wakes up every hour and runs under a distributed lock so that only one node executes it in HA deployments.
- **Pure Orphans:** Deletes DCR clients that have never had a user linked and exceeded the grace period (default 24 h).
- **Linked Duplicates:** Groups DCR clients by `(linked_user_id, fingerprint)` and prunes them according to the configured strategy (see below).

## Cleanup Strategies

The cleanup behavior for **linked duplicates** is configurable.

### Strategy A — "Last wins" (default)

- For groups with more than one client, keep the one with the most recent `used_at` and delete the rest.
- Lonely groups (1 client only) are **never** deleted.

### Strategy B — "Inactivity grace"

- Delete clients whose `used_at` is older than `INACTIVITY_DAYS`.
- Lonely inactive clients are deleted **only if** `DELETE_LONELY_INACTIVE=true`.

## Client Attributes

| Attribute        | When written        | Description                                           |
| ---------------- | ------------------- | ----------------------------------------------------- |
| `is_dcr_client`  | On DCR registration | `true` for clients tagged by this SPI.                |
| `created_at`     | On DCR registration | Epoch ms of registration.                             |
| `fingerprint`    | On DCR registration | Deterministic SHA-256 of redirect URIs + client name. |
| `linked_user_id` | On every login      | UUID of the user who logged in.                       |
| `used_at`        | On every login      | Epoch ms of the most recent login.                    |

## Configuration via Environment Variables

| Variable                                 | Default | Description                                                       |
| ---------------------------------------- | ------- | ----------------------------------------------------------------- |
| `DCR_LIFECYCLE_STRATEGY`                 | `A`     | Cleanup strategy for linked duplicates: `A` or `B`.               |
| `DCR_LIFECYCLE_INACTIVITY_DAYS`          | `30`    | Days of inactivity before a client is deletable under strategy B. |
| `DCR_LIFECYCLE_DELETE_LONELY_INACTIVE`   | `false` | If `true`, strategy B also deletes lonely inactive clients.       |
| `DCR_LIFECYCLE_GRACE_PERIOD_HOURS`       | `24`    | Hours of grace before deleting pure orphans.                      |
| `DCR_LIFECYCLE_CLEANUP_INTERVAL_MINUTES` | `60`    | How often the cleanup task runs.                                  |

These values are read **once on startup**.

## How the Fingerprint is Calculated

The `fingerprint` acts as a deterministic grouping identifier for DCR clients originating from the same platform/app:

1. Extract the `scheme` and `host` (e.g., `https://claude.ai`, `cursor://auth`) from every registered `redirect_uris`.
2. Sort the origins alphabetically and remove duplicates.
3. Concatenate them with the `clientName` (e.g., `https://claude.ai|Claude`).
4. Apply SHA-256 to keep the attribute size bounded.

## Build and Run

You don't need Java or Maven installed on your machine. Everything runs via Docker containers.

```bash
# Compile and build the JAR
make package

# Run a local Keycloak instance with the SPI installed
make run

# Run integration tests against the local Keycloak
make integration-test

# Stop the local Keycloak
make stop
```

## How to Enable in Keycloak

Once the `.jar` is deployed to your Keycloak's `providers/` directory and the server is restarted, you must explicitly enable the Event Listener in your realm:

1. Open the Keycloak Admin Console.
2. Select your Realm.
3. Go to **Realm settings** in the left menu.
4. Go to the **Events** tab.
5. In the **Event Listeners** field, add `dcr-lifecycle-manager`.
6. Save the changes.

_(Note: The integration test script does this automatically via the Admin REST API)._

## How to Check Attributes

Although the new Keycloak Admin Console does not show custom Client attributes natively in the UI, this SPI stores everything perfectly in the `CLIENT_ATTRIBUTES` database table. You can verify all five attributes by fetching the client via the Keycloak REST API.

---

Created with 💖 by Alby Hernández
