# Copyright 2026 Freepik Company S.L.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Concurrency / race-condition test for the LOGIN hot path.

The original implementation deleted older clients synchronously inside the
LOGIN event handler, which caused two simultaneous logins of the same user
on different DCR clients to delete each other (mutual deletion).

The current implementation moves all deletion logic to the scheduled task
(Phase 2), so the LOGIN hot path is now non-destructive. This test verifies
that property by:

  1. Creating N DCR clients sharing the same fingerprint.
  2. Logging in to all of them concurrently from N threads.
  3. Asserting that all N clients still exist after the storm and that each
     one has its `linked_user_id` and `used_at` attributes properly set.

If any client got deleted during the LOGIN phase, the test fails.
"""

import urllib.request
import json
import time
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE_URL = "http://localhost:8081"
N_CLIENTS = 8


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


def get_clients(token):
    url = f"{BASE_URL}/admin/realms/master/clients"
    req = urllib.request.Request(url, method="GET")
    req.add_header('Authorization', f'Bearer {token}')
    with urllib.request.urlopen(req) as response:
        return json.loads(response.read().decode())


def user_login(client_id, barrier):
    """Block on the barrier so that all threads launch the login at the same instant."""
    barrier.wait()
    url = f"{BASE_URL}/realms/master/protocol/openid-connect/token"
    data = f"username=admin&password=admin&grant_type=password&client_id={client_id}".encode('utf-8')
    req = urllib.request.Request(url, data=data)
    req.add_header('Content-Type', 'application/x-www-form-urlencoded')
    try:
        urllib.request.urlopen(req).read()
        return (client_id, True, None)
    except Exception as e:
        return (client_id, False, str(e))


def main():
    print("Waiting for Keycloak...")
    time.sleep(5)

    token = get_admin_token()
    print("Got admin token.")
    enable_event_listener(token)
    print("Event listeners configured.")

    print(f"\n--- Creating {N_CLIENTS} DCR clients with identical fingerprint ---")
    client_ids = []
    for _ in range(N_CLIENTS):
        cid = create_dcr_client(token, "Claude", ["https://claude.ai/cb"])
        client_ids.append(cid)
    print(f"Clients: {client_ids}")

    print(f"\n--- Launching {N_CLIENTS} simultaneous logins ---")
    barrier = threading.Barrier(N_CLIENTS)
    results = []
    with ThreadPoolExecutor(max_workers=N_CLIENTS) as pool:
        futures = [pool.submit(user_login, cid, barrier) for cid in client_ids]
        for fut in as_completed(futures):
            results.append(fut.result())

    successful = [r for r in results if r[1]]
    failed = [r for r in results if not r[1]]
    print(f"   Successful logins: {len(successful)}/{N_CLIENTS}")
    if failed:
        for cid, _, err in failed:
            print(f"   ❌ Login failed for {cid}: {err}")

    # Give Keycloak a couple of seconds to flush all attribute writes
    time.sleep(3)

    print("\n--- Verifying that no client was deleted during the LOGIN storm ---")
    clients = get_clients(token)
    surviving = {c['clientId'] for c in clients}
    failures = []

    for cid in client_ids:
        if cid not in surviving:
            failures.append(f"Client {cid} disappeared during the LOGIN storm")

    print("\n--- Verifying that each client has linked_user_id and used_at set ---")
    for c in clients:
        if c['clientId'] in client_ids:
            attrs = c.get('attributes', {})
            if not attrs.get('linked_user_id'):
                failures.append(f"Client {c['clientId']} missing linked_user_id")
            if not attrs.get('used_at'):
                failures.append(f"Client {c['clientId']} missing used_at")

    print("\n--- Results ---")
    if failures:
        for f in failures:
            print(f"❌ {f}")
        sys.exit(1)
    else:
        print(f"✅ All {N_CLIENTS} clients survived the simultaneous LOGIN storm")
        print("✅ Each client has linked_user_id and used_at correctly set")
        print("✅ Hot path is race-condition free (deletion deferred to Phase 2)")


if __name__ == "__main__":
    main()
