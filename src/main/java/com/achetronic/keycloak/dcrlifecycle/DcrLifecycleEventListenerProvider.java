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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * EventListenerProvider implementation for DCR Lifecycle Manager.
 * Intercepts standard OIDC Client Registration events to tag them, and Login
 * events to link users and delete older unused DCR clients with the same fingerprint.
 */
public class DcrLifecycleEventListenerProvider implements EventListenerProvider {

    private static final Logger log = Logger.getLogger(DcrLifecycleEventListenerProvider.class);

    public static final String ATTR_DCR_CREATED_AT = "dcr_created_at";
    public static final String ATTR_DCR_FINGERPRINT = "dcr_fingerprint";
    public static final String ATTR_LINKED_USER_ID = "linked_user_id";

    private static final String UNKNOWN_CLIENT_NAME = "Unknown";
    private static final String NONE_PLACEHOLDER = "none";
    private static final String WILDCARD_URI = "*";

    private final KeycloakSession session;

    public DcrLifecycleEventListenerProvider(KeycloakSession session) {
        this.session = session;
    }

    /**
     * Handles standard Keycloak events.
     * Looks for CLIENT_REGISTER events to tag newly created DCR clients,
     * and LOGIN events to link the user to a DCR client and trigger garbage collection.
     *
     * @param event The triggered Keycloak event.
     */
    @Override
    public void onEvent(Event event) {
        if (event == null || event.getError() != null) {
            // Ignore failed events to avoid tagging or linking on errors
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
     * Tags a newly created DCR client with its creation timestamp and fingerprint.
     *
     * @param event The CLIENT_REGISTER event.
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
            clientModel.setAttribute(ATTR_DCR_CREATED_AT, String.valueOf(now));
            clientModel.setAttribute(ATTR_DCR_FINGERPRINT, fingerprint);

            log.infof("Tagged new DCR client %s with fingerprint: %s", clientModel.getClientId(), fingerprint);
        } catch (Exception e) {
            log.error("Failed to process CLIENT_REGISTER event for new DCR client", e);
        }
    }

    /**
     * Links the logged-in user to the DCR client and triggers cleanup of older
     * duplicate clients for the same user/platform.
     *
     * @param event The LOGIN event.
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

        // Only act on DCR clients that have been previously tagged
        String dcrCreatedAt = currentClient.getAttribute(ATTR_DCR_CREATED_AT);
        if (dcrCreatedAt == null) {
            return;
        }

        // Link the user to the client
        log.infof("Linking user %s to DCR client %s", userId, currentClient.getId());
        currentClient.setAttribute(ATTR_LINKED_USER_ID, userId);

        // Trigger cleanup of older clients from the same provider/user
        String currentFingerprint = currentClient.getAttribute(ATTR_DCR_FINGERPRINT);
        if (currentFingerprint != null) {
            try {
                cleanupOldClients(realm, userId, currentClient.getId(), currentFingerprint);
            } catch (Exception e) {
                // Never let cleanup errors break a successful login flow
                log.errorf(e, "Cleanup of older DCR clients failed for user %s, client %s", userId, currentClient.getId());
            }
        }
    }

    /**
     * Cleans up older DCR clients belonging to the same user and platform (fingerprint).
     * Materializes the candidate list before deletion to avoid concurrent modification
     * issues while iterating the client stream.
     *
     * @param realm             The current Keycloak Realm.
     * @param userId            The user ID that logged in.
     * @param currentClientUuid The UUID of the client currently being used.
     * @param fingerprint       The deterministic fingerprint of the platform.
     */
    private void cleanupOldClients(RealmModel realm, String userId, String currentClientUuid, String fingerprint) {
        log.debugf("Starting cleanup for user %s, fingerprint: %s", userId, fingerprint);

        // Materialize the list of UUIDs to delete BEFORE removing them, to avoid
        // ConcurrentModificationException or undefined behavior when iterating the stream.
        List<String> clientsToDelete = session.clients().getClientsStream(realm)
                .filter(c -> userId.equals(c.getAttribute(ATTR_LINKED_USER_ID)))
                .filter(c -> fingerprint.equals(c.getAttribute(ATTR_DCR_FINGERPRINT)))
                .filter(c -> !currentClientUuid.equals(c.getId()))
                .map(ClientModel::getId)
                .collect(Collectors.toList());

        for (String uuid : clientsToDelete) {
            ClientModel oldClient = session.clients().getClientById(realm, uuid);
            if (oldClient == null) {
                continue;
            }
            log.infof("Deleting orphaned DCR client %s for user %s", oldClient.getClientId(), userId);
            session.clients().removeClient(realm, uuid);
        }
    }

    /**
     * Handles Keycloak Admin events.
     * We deliberately ignore Admin events because we only want to tag clients created
     * via the standard OIDC DCR endpoint (CLIENT_REGISTER event), not clients created
     * manually through the Admin REST API or the admin console.
     *
     * @param event                 The triggered Admin event.
     * @param includeRepresentation Whether the event includes the resource representation.
     */
    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // Intentionally ignored to prevent tagging and deleting manually created clients.
    }

    /**
     * Calculates a deterministic SHA-256 fingerprint based on the client's redirect URIs
     * and name. Extracts scheme and host from each URI, sorts them, concatenates them
     * with the client name, and applies SHA-256 to keep the attribute size bounded.
     *
     * @param client The Keycloak ClientModel.
     * @return A SHA-256 hex string identifying the client's origins.
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

        String rawFingerprint = sortedUris + "|" + clientName;
        return sha256Hex(rawFingerprint);
    }

    /**
     * Computes the SHA-256 hash of the given input as a lowercase hexadecimal string.
     * Falls back to the raw input if SHA-256 is somehow unavailable in the JVM.
     *
     * @param input The string to hash.
     * @return Lowercase hexadecimal SHA-256 digest.
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
            // Extremely unlikely; fall back to the raw value rather than failing the registration
            log.error("SHA-256 not available, falling back to raw fingerprint", e);
            return input;
        }
    }

    @Override
    public void close() {
        // Nothing to close
    }
}
