# Keycloak DCR Lifecycle Manager

A Keycloak Event Listener SPI that elegantly manages the lifecycle of Dynamic Client Registration (DCR) clients. 

## The Problem
Applications and MCPs (like ChatGPT, Claude, Zed, Cursor) use DCR to dynamically create a new Keycloak Client for each integration. Because the OAuth2 standard doesn't natively link a dynamically created client to the user who subsequently logs into it, this results in:
1. A massive explosion of orphaned clients in the Keycloak database.
2. Leftover clients from abandoned login attempts.
3. Multiple active clients for the same user if they integrate from different devices.

## The Solution

This SPI implements a complete garbage collection and linking system in two phases:

### Phase 1: Real-time Tagging & Linking
* **Tagging on Creation:** Intercepts `AdminEvent` when a client is created. It automatically injects an exact timestamp (`dcr_created_at`) and a deterministically calculated Fingerprint (`dcr_fingerprint` based on redirect URIs and client name).
* **Linking & Cleanup on Login:** Intercepts `Event` on user login. If the client used is a DCR client, it links the user to it (`linked_user_id`). Immediately after, it safely deletes all older clients belonging to that exact user and fingerprint.

### Phase 2: Asynchronous Garbage Collection
* **Orphan Cleanup:** A Cluster-Aware scheduled task (`TimerProvider` + `ClusterProvider`) wakes up every hour.
* **Logic:** Finds any DCR client that is older than 24 hours and has *never* had a user linked to it (meaning the user abandoned the login flow).
* **High Availability:** Uses Distributed Locks to ensure that in a multi-node Keycloak cluster, only one node performs the cleanup.

## How the Fingerprint is Calculated
The `dcr_fingerprint` acts as a deterministic grouping identifier for DCR clients originating from the same platform/app. It is calculated by:
1. Extracting the `scheme` and `host` (e.g., `https://claude.ai`, `cursor://auth`) from every registered `redirect_uris`.
2. Sorting these origins alphabetically and removing duplicates.
3. Concatenating the result with the `clientName` (e.g., `https://claude.ai|Claude`).
4. Applying an SHA-256 hash to ensure a standardized length.

## Build and Run

You don't need Java or Maven installed on your machine. Everything runs via Docker containers.

```bash
# Compile and build the JAR
make package

# Run a local Keycloak instance with the SPI installed
make run

# Run integration tests
python3 test_spi.py

# Stop the local Keycloak
make stop
```

## How to Check Attributes

Although the new Keycloak Admin Console does not show custom Client attributes natively in the UI, this SPI stores everything perfectly in the `CLIENT_ATTRIBUTES` database table. You can verify the `dcr_created_at`, `dcr_fingerprint`, and `linked_user_id` attributes by fetching the client via the Keycloak REST API.

---

Created with 💖 by Alby Hernández