"""
End-to-end integration test for Phase 2 (the orphan cleanup scheduled task).

This test relies on running Keycloak with very aggressive timing:
- DCR_LIFECYCLE_GRACE_PERIOD_HOURS=0  -> orphans deleted immediately
- DCR_LIFECYCLE_CLEANUP_INTERVAL_MINUTES=1 -> task runs every minute

The test:
  1. Creates DCR clients (some orphans, some linked) and a manual client.
  2. Performs logins to vary the (linked_user_id, fingerprint) groups.
  3. Waits ~70 seconds for the cleanup task to fire.
  4. Asserts that:
       - Pure orphans were deleted.
       - Strategy A kept the most-recently-used linked client per group
         and removed the older duplicates.
       - The manual client was untouched.
"""

import urllib.request
import json
import time
import sys

BASE_URL = "http://localhost:8081"
CLEANUP_WAIT_SECONDS = 75


def get_admin_token():
    url = f"{BASE_URL}/realms/master/protocol/openid-connect/token"
    data = b"username=admin&password=admin&grant_type=password&client_id=admin-cli"
    req = urllib.request.Request(url, data=data)
    req.add_header('Content-Type', 'application/x-www-form-urlencoded')
    with urllib.request.urlopen(req) as response:
        return json.loads(response.read().decode())['access_token']


def enable_event_listener(token):
    url = f"{BASE_URL}/admin/realms/master/events/config"
    req = urllib.request.Request(url, method="GET")
    req.add_header('Authorization', f'Bearer {token}')
    with urllib.request.urlopen(req) as response:
        config = json.loads(response.read().decode())
    listeners = config.get('eventsListeners', [])
    if 'dcr-lifecycle-manager' not in listeners:
        listeners.append('dcr-lifecycle-manager')
    config['eventsListeners'] = listeners
    config['eventsEnabled'] = True
    config['adminEventsEnabled'] = True
    req = urllib.request.Request(url, data=json.dumps(config).encode('utf-8'), method="PUT")
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Content-Type', 'application/json')
    urllib.request.urlopen(req).read()


def create_dcr_client(token, name, redirect_uris):
    url = f"{BASE_URL}/realms/master/clients-registrations/openid-connect"
    payload = {
        "client_name": name,
        "redirect_uris": redirect_uris,
        "token_endpoint_auth_method": "none",
        "grant_types": ["password", "authorization_code"],
    }
    req = urllib.request.Request(url, data=json.dumps(payload).encode('utf-8'), method="POST")
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(req) as response:
        return json.loads(response.read().decode())['client_id']


def create_manual_client(token, client_id, name, redirect_uris):
    url = f"{BASE_URL}/admin/realms/master/clients"
    payload = {
        "clientId": client_id,
        "name": name,
        "redirectUris": redirect_uris,
        "publicClient": True,
        "directAccessGrantsEnabled": True,
    }
    req = urllib.request.Request(url, data=json.dumps(payload).encode('utf-8'), method="POST")
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Content-Type', 'application/json')
    urllib.request.urlopen(req).read()
    return client_id


def get_clients(token):
    url = f"{BASE_URL}/admin/realms/master/clients"
    req = urllib.request.Request(url, method="GET")
    req.add_header('Authorization', f'Bearer {token}')
    with urllib.request.urlopen(req) as response:
        return json.loads(response.read().decode())


def user_login(client_id):
    url = f"{BASE_URL}/realms/master/protocol/openid-connect/token"
    data = f"username=admin&password=admin&grant_type=password&client_id={client_id}".encode('utf-8')
    req = urllib.request.Request(url, data=data)
    req.add_header('Content-Type', 'application/x-www-form-urlencoded')
    urllib.request.urlopen(req).read()


def client_exists(clients, client_id):
    return any(c['clientId'] == client_id for c in clients)


def main():
    print("Waiting for Keycloak...")
    time.sleep(5)

    token = get_admin_token()
    print("Got admin token.")
    enable_event_listener(token)
    print("Event listeners configured.")

    # --- Setup ---
    # Two pure orphans (no login) - should be deleted by Pass 1
    print("\n--- Creating pure orphan clients ---")
    orphan_chatgpt = create_dcr_client(token, "ChatGPT-Orphan", ["https://chatgpt.com/cb"])
    orphan_zed = create_dcr_client(token, "Zed-Orphan", ["zed://auth/cb"])
    print(f"Orphans created: {orphan_chatgpt}, {orphan_zed}")

    # Three Claude clients to test Strategy A: most-recent-used wins
    print("\n--- Creating Claude clients to exercise Strategy A duplicates ---")
    claude_old = create_dcr_client(token, "Claude", ["https://claude.ai/cb"])
    claude_mid = create_dcr_client(token, "Claude", ["https://claude.ai/cb"])
    claude_winner = create_dcr_client(token, "Claude", ["https://claude.ai/cb"])
    print(f"Claude clients: old={claude_old}, mid={claude_mid}, winner={claude_winner}")

    # Manual client - must NOT be touched
    print("\n--- Creating manual client ---")
    manual = create_manual_client(token, "manual-untouchable", "Manual", ["https://manual.com/cb"])

    # Login order matters: winner is logged-in last so it has the most recent used_at.
    print("\n--- Performing logins (oldest -> newest) ---")
    user_login(claude_old)
    time.sleep(2)
    user_login(claude_mid)
    time.sleep(2)
    user_login(claude_winner)

    # --- Wait for Phase 2 to fire ---
    print(f"\n--- Waiting {CLEANUP_WAIT_SECONDS}s for the cleanup task to run ---")
    time.sleep(CLEANUP_WAIT_SECONDS)

    # Refresh the admin token because it likely expired during the wait.
    token = get_admin_token()

    # --- Verify ---
    clients = get_clients(token)
    failures = []

    # Pure orphans must be gone
    if client_exists(clients, orphan_chatgpt):
        failures.append(f"Orphan {orphan_chatgpt} (ChatGPT) was NOT deleted")
    if client_exists(clients, orphan_zed):
        failures.append(f"Orphan {orphan_zed} (Zed) was NOT deleted")

    # Strategy A: only the winner survives
    if client_exists(clients, claude_old):
        failures.append(f"Older Claude {claude_old} was NOT deleted (Strategy A failure)")
    if client_exists(clients, claude_mid):
        failures.append(f"Mid Claude {claude_mid} was NOT deleted (Strategy A failure)")
    if not client_exists(clients, claude_winner):
        failures.append(f"Winner Claude {claude_winner} was wrongly deleted")

    # Manual client must remain
    if not client_exists(clients, manual):
        failures.append(f"Manual client {manual} was wrongly deleted")
    else:
        for c in clients:
            if c['clientId'] == manual:
                attrs = c.get('attributes', {})
                if attrs.get('is_dcr_client'):
                    failures.append(f"Manual client {manual} has DCR attributes")

    print("\n--- Results ---")
    if failures:
        for f in failures:
            print(f"❌ {f}")
        sys.exit(1)
    else:
        print("✅ Phase 2 cleanup behaved as expected:")
        print("   - Pure orphans deleted")
        print("   - Strategy A kept only the most-recently-used Claude client")
        print("   - Manual client untouched")


if __name__ == "__main__":
    main()
