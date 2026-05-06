import urllib.request
import json
import time
import sys

BASE_URL = "http://localhost:8081"

def get_admin_token():
    url = f"{BASE_URL}/realms/master/protocol/openid-connect/token"
    data = b"username=admin&password=admin&grant_type=password&client_id=admin-cli"
    req = urllib.request.Request(url, data=data)
    req.add_header('Content-Type', 'application/x-www-form-urlencoded')
    try:
        with urllib.request.urlopen(req) as response:
            res = json.loads(response.read().decode())
            return res['access_token']
    except Exception as e:
        print("Error getting token:", e)
        sys.exit(1)

def enable_event_listener(token):
    url = f"{BASE_URL}/admin/realms/master/events/config"
    req = urllib.request.Request(url, method="GET")
    req.add_header('Authorization', f'Bearer {token}')
    with urllib.request.urlopen(req) as response:
        config = json.loads(response.read().decode())
    
    listeners = config.get('eventsListeners', [])
    if 'dcr-user-linker' not in listeners:
        listeners.append('dcr-user-linker')
    config['eventsListeners'] = listeners
    config['adminEventsEnabled'] = True
    config['eventsEnabled'] = True

    req = urllib.request.Request(url, data=json.dumps(config).encode('utf-8'), method="PUT")
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(req) as response:
        print("Event listeners configured.")

def create_client(token, client_id, name, redirect_uris):
    url = f"{BASE_URL}/admin/realms/master/clients"
    data = {
        "clientId": client_id,
        "name": name,
        "redirectUris": redirect_uris,
        "publicClient": True,
        "directAccessGrantsEnabled": True
    }
    req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), method="POST")
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Content-Type', 'application/json')
    try:
        urllib.request.urlopen(req)
        print(f"Client {client_id} created.")
    except Exception as e:
        print(f"Error creating client {client_id}: {e}")

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
    try:
        with urllib.request.urlopen(req) as response:
            print(f"Login successful for client {client_id}.")
    except Exception as e:
        print(f"Error logging in with client {client_id}: {e}")

print("Waiting for Keycloak...")
time.sleep(5) # Give it a bit more time if needed

token = get_admin_token()
print("Got admin token.")
enable_event_listener(token)

print("\n--- Creating Clients ---")
create_client(token, "chatgpt-1", "ChatGPT", ["https://chatgpt.com/cb"])
create_client(token, "claude-1", "Claude", ["https://claude.ai/cb"])
create_client(token, "claude-2", "Claude", ["https://claude.ai/cb"])
create_client(token, "claude-3", "Claude", ["https://claude.ai/cb"])

print("\n--- Checking Attributes Before Login ---")
clients = get_clients(token)
for c in clients:
    if c['clientId'].startswith('claude') or c['clientId'].startswith('chatgpt'):
        attrs = c.get('attributes', {})
        print(f"{c['clientId']}:")
        print(f"  dcr_created_at: {attrs.get('dcr_created_at')}")
        print(f"  dcr_fingerprint: {attrs.get('dcr_fingerprint')}")
        print(f"  linked_user_id: {attrs.get('linked_user_id')}")

print("\n--- Performing Login with claude-1 ---")
user_login("claude-1")

print("\n--- Performing Login with claude-3 ---")
user_login("claude-3")

print("\n--- Checking Clients After Login ---")
clients = get_clients(token)
existing_clients = [c['clientId'] for c in clients if c['clientId'].startswith('claude') or c['clientId'].startswith('chatgpt')]
print("Remaining test clients:", existing_clients)

for c in clients:
    if c['clientId'] == 'claude-3':
        attrs = c.get('attributes', {})
        print(f"claude-3 attributes after login:")
        print(f"  linked_user_id: {attrs.get('linked_user_id')}")
