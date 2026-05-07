/*
 * Copyright 2026 Freepik Company S.L.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.achetronic.keycloak.dcrlifecycle;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * EventListenerProvider implementation for DCR Lifecycle Manager.
 * <p>
 * Handles two phases of the SPI:
 * <ul>
 *   <li><b>CLIENT_REGISTER</b>: tags newly created DCR clients with metadata
 *       ({@code is_dcr_client}, {@code created_at}, {@code fingerprint}).</li>
 *   <li><b>LOGIN</b>: marks the client as used by a given user
 *       ({@code linked_user_id}, {@code used_at}).</li>
 * </ul>
 * The actual cleanup of duplicates and orphans is delegated to
 * {@link DcrOrphanCleanupTask} to keep this hot path as fast as possible.
 */
public class DcrLifecycleEventListenerProvider implements EventListenerProvider {

    private static final Logger log = Logger.getLogger(DcrLifecycleEventListenerProvider.class);

    /** Marks a client as having been created via standard OIDC DCR. */
    public static final String ATTR_IS_DCR_CLIENT = "is_dcr_client";
    /** Timestamp (epoch ms) when the DCR client was registered. */
    public static final String ATTR_CREATED_AT = "created_at";
    /** Deterministic SHA-256 fingerprint identifying the platform/app behind the client. */
    public static final String ATTR_FINGERPRINT = "fingerprint";
    /** Keycloak user UUID of the user that has logged in with this DCR client. */
    public static final String ATTR_LINKED_USER_ID = "linked_user_id";
    /** Timestamp (epoch ms) of the last successful login on this DCR client. */
    public static final String ATTR_USED_AT = "used_at";

    private static final String UNKNOWN_CLIENT_NAME = "Unknown";
    private static final String NONE_PLACEHOLDER = "none";
    private static final String WILDCARD_URI = "*";

    private final KeycloakSession session;

    public DcrLifecycleEventListenerProvider(KeycloakSession session) {
        this.session = session;
    }

    /**
     * Routes Keycloak events to the appropriate handler.
     * Failed events (with error) are intentionally ignored to avoid tagging
     * or linking on broken flows.
     */
    @Override
    public void onEvent(Event event) {
        if (event == null || event.getError() != null) {
            return;
        }

        if (event.getType() == EventType.CLIENT_REGISTER) {
            handleClientRegister(event);
            return;
        }

        if (event.getType() == EventType.LOGIN) {
            handleLogin(event);
        }
    }

    /**
     * Tags a newly created DCR client with its identifying metadata.
     */
    private void handleClientRegister(Event event) {
        String clientId = event.getClientId();
        String realmId = event.getRealmId();

        if (clientId == null || realmId == null) {
            return;
        }

        RealmModel realm = session.realms().getRealm(realmId);
        if (realm == null) {
            return;
        }

        ClientModel clientModel = session.clients().getClientByClientId(realm, clientId);
        if (clientModel == null) {
            return;
        }

        try {
            String fingerprint = calculateFingerprint(clientModel);
            long now = System.currentTimeMillis();

            clientModel.setAttribute(ATTR_IS_DCR_CLIENT, "true");
            clientModel.setAttribute(ATTR_CREATED_AT, String.valueOf(now));
            clientModel.setAttribute(ATTR_FINGERPRINT, fingerprint);

            log.infof("Tagged new DCR client %s with fingerprint: %s", clientModel.getClientId(), fingerprint);
        } catch (Exception e) {
            log.error("Failed to process CLIENT_REGISTER event for new DCR client", e);
        }
    }

    /**
     * Marks the client as used by the logged-in user. The actual cleanup of
     * duplicates is performed asynchronously by {@link DcrOrphanCleanupTask},
     * keeping this hot path lightweight.
     */
    private void handleLogin(Event event) {
        String userId = event.getUserId();
        String clientId = event.getClientId();
        String realmId = event.getRealmId();

        if (userId == null || clientId == null || realmId == null) {
            return;
        }

        RealmModel realm = session.realms().getRealm(realmId);
        if (realm == null) {
            return;
        }

        ClientModel currentClient = session.clients().getClientByClientId(realm, clientId);
        if (currentClient == null) {
            return;
        }

        // Fast filter: only act on DCR-tagged clients
        if (!"true".equals(currentClient.getAttribute(ATTR_IS_DCR_CLIENT))) {
            return;
        }

        // Ownership guard: once a DCR client is linked to a user, it cannot be transferred.
        // Otherwise an attacker could register a DCR client with a forgeable fingerprint
        // (deterministic over public data: redirect_uris + client name), phish the victim
        // into completing a flow against it, and trigger Strategy A to delete the victim's
        // legitimate client during the next cleanup pass.
        String existingUserId = currentClient.getAttribute(ATTR_LINKED_USER_ID);
        if (existingUserId != null && !existingUserId.equals(userId)) {
            log.warnf("Refusing to transfer DCR client %s ownership: %s -> %s",
                    currentClient.getClientId(), existingUserId, userId);
            return;
        }

        long now = System.currentTimeMillis();
        currentClient.setAttribute(ATTR_LINKED_USER_ID, userId);
        currentClient.setAttribute(ATTR_USED_AT, String.valueOf(now));

        log.debugf("Marked DCR client %s as used by user %s", currentClient.getClientId(), userId);
    }

    /**
     * Admin events are intentionally ignored: only DCR-flow CLIENT_REGISTER events
     * tag clients, so manually created clients are left untouched.
     */
    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // Intentionally ignored.
    }

    /**
     * Calculates a deterministic SHA-256 fingerprint from the client's redirect URIs
     * and client name. This groups DCR clients originating from the same
     * platform/app (e.g., all Claude clients share a fingerprint).
     */
    // Visibility changed to package-private for testing
    String calculateFingerprint(ClientModel client) {
        Set<String> schemeHosts = new HashSet<>();

        Set<String> redirectUris = client.getRedirectUris();
        if (redirectUris != null) {
            for (String uriString : redirectUris) {
                if (uriString == null || WILDCARD_URI.equals(uriString)) {
                    continue;
                }
                try {
                    URI uri = new URI(uriString);
                    String scheme = uri.getScheme() != null ? uri.getScheme() : NONE_PLACEHOLDER;
                    String host = uri.getHost() != null ? uri.getHost() : NONE_PLACEHOLDER;
                    schemeHosts.add(scheme + "://" + host);
                } catch (Exception e) {
                    log.debugf("Could not parse redirect URI for fingerprinting: %s", uriString);
                }
            }
        }

        String sortedUris = schemeHosts.stream()
                .sorted()
                .collect(Collectors.joining(","));

        String clientName = client.getName() != null ? client.getName() : UNKNOWN_CLIENT_NAME;

        return sha256Hex(sortedUris + "|" + clientName);
    }

    /**
     * Computes the SHA-256 hash of the given input as a lowercase hexadecimal string.
     */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available, falling back to raw fingerprint", e);
            return input;
        }
    }

    @Override
    public void close() {
        // Nothing to close
    }
}
