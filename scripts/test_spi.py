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
    if 'dcr-lifecycle-manager' not in listeners:
        listeners.append('dcr-lifecycle-manager')
    config['eventsListeners'] = listeners
    config['adminEventsEnabled'] = True
    config['eventsEnabled'] = True

    req = urllib.request.Request(url, data=json.dumps(config).encode('utf-8'), method="PUT")
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(req) as response:
        print("Event listeners configured.")

def create_client(token, client_id, name, redirect_uris):
    # Using the standard OIDC DCR endpoint instead of Admin REST API
    url = f"{BASE_URL}/realms/master/clients-registrations/openid-connect"
    data = {
        "client_name": name,
        "redirect_uris": redirect_uris,
        "token_endpoint_auth_method": "none", # public client
        "grant_types": ["password", "authorization_code"]
    }
    req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), method="POST")
    # Using admin token as Bearer for DCR works in Keycloak
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(req) as response:
            res = json.loads(response.read().decode())
            generated_client_id = res['client_id']
            print(f"Client {generated_client_id} created via DCR.")
            return generated_client_id
    except Exception as e:
        print(f"Error creating client {name}: {e}")
        return None

def create_manual_client(token, client_id, name, redirect_uris):
    # Using the Admin REST API endpoint to simulate a manual creation
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
        print(f"Manual Client {client_id} created.")
        return client_id
    except Exception as e:
        print(f"Error creating manual client {client_id}: {e}")
        return None

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

print("\n--- Creating Clients via DCR ---")
chatgpt_1 = create_client(token, "chatgpt-1", "ChatGPT", ["https://chatgpt.com/cb"])
claude_1 = create_client(token, "claude-1", "Claude", ["https://claude.ai/cb"])
claude_2 = create_client(token, "claude-2", "Claude", ["https://claude.ai/cb"])
claude_3 = create_client(token, "claude-3", "Claude", ["https://claude.ai/cb"])

print("\n--- Creating Manual Client ---")
# Using the same name and redirect URI as Claude to test that it is NOT deleted because it lacks DCR tags
manual_1 = create_manual_client(token, "manual-claude-1", "Claude", ["https://claude.ai/cb"])

test_client_ids = [chatgpt_1, claude_1, claude_2, claude_3, manual_1]

print("\n--- Checking Attributes Before Login ---")
clients = get_clients(token)
for c in clients:
    if c['clientId'] in test_client_ids:
        attrs = c.get('attributes', {})
        print(f"{c['clientId']} ({c.get('name')}):")
        print(f"  dcr_created_at: {attrs.get('dcr_created_at')}")
        print(f"  dcr_fingerprint: {attrs.get('dcr_fingerprint')}")
        print(f"  linked_user_id: {attrs.get('linked_user_id')}")

print(f"\n--- Performing Login with {claude_1} ---")
user_login(claude_1)

print(f"\n--- Performing Login with {claude_3} ---")
user_login(claude_3)

print(f"\n--- Performing Login with {manual_1} (Manual Client) ---")
user_login(manual_1)

print("\n--- Checking Clients After Login ---")
clients = get_clients(token)
existing_clients = [c['clientId'] for c in clients if c['clientId'] in test_client_ids]
print("Remaining test clients:", existing_clients)

for c in clients:
    if c['clientId'] == claude_3:
        attrs = c.get('attributes', {})
        print(f"{claude_3} attributes after login:")
        print(f"  linked_user_id: {attrs.get('linked_user_id')}")
