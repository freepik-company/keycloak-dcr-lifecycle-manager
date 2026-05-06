package com.achetronic.keycloak.dcrlifecycle;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.util.JsonSerialization;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * EventListenerProvider implementation for DCR Lifecycle Manager.
 * Intercepts Client Creation events to tag them, and Login events to link
 * users and delete older unused DCR clients with the same fingerprint.
 */
public class DcrLifecycleEventListenerProvider implements EventListenerProvider {

    private static final Logger log = Logger.getLogger(DcrLifecycleEventListenerProvider.class);
    
    public static final String ATTR_DCR_CREATED_AT = "dcr_created_at";
    public static final String ATTR_DCR_FINGERPRINT = "dcr_fingerprint";
    public static final String ATTR_LINKED_USER_ID = "linked_user_id";

    private final KeycloakSession session;

    public DcrLifecycleEventListenerProvider(KeycloakSession session) {
        this.session = session;
    }

    /**
     * Handles standard Keycloak events.
     * Specifically looks for successful LOGIN events to link the user to a DCR client
     * and trigger the garbage collection of older duplicate clients.
     *
     * @param event The triggered Keycloak event.
     */
    @Override
    public void onEvent(Event event) {
        // We only care about successful logins
        if (event.getType() != EventType.LOGIN) {
            return;
        }

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

        // 1. Is this a DCR client?
        String dcrCreatedAt = currentClient.getAttribute(ATTR_DCR_CREATED_AT);
        if (dcrCreatedAt == null) {
            // Not a DCR client, ignore
            return;
        }

        // 2. Link the user to the client
        log.infof("Linking user %s to DCR client %s", userId, currentClient.getId());
        currentClient.setAttribute(ATTR_LINKED_USER_ID, userId);

        // 3. Cleanup older clients from the same provider
        String currentFingerprint = currentClient.getAttribute(ATTR_DCR_FINGERPRINT);
        if (currentFingerprint != null) {
            cleanupOldClients(realm, userId, currentClient.getId(), currentFingerprint);
        }
    }

    /**
     * Cleans up older DCR clients belonging to the same user and platform (fingerprint).
     *
     * @param realm             The current Keycloak Realm.
     * @param userId            The user ID that logged in.
     * @param currentClientUuid The UUID of the client currently being used.
     * @param fingerprint       The deterministic fingerprint of the platform.
     */
    private void cleanupOldClients(RealmModel realm, String userId, String currentClientUuid, String fingerprint) {
        log.debugf("Starting cleanup for user %s, fingerprint: %s", userId, fingerprint);
        
        // Find all clients in the realm (we might want to optimize this in huge realms, 
        // but Keycloak's search by attribute is limited in the SPI)
        session.clients().getClientsStream(realm)
                .filter(c -> userId.equals(c.getAttribute(ATTR_LINKED_USER_ID))) // Belongs to user
                .filter(c -> fingerprint.equals(c.getAttribute(ATTR_DCR_FINGERPRINT))) // Same provider
                .filter(c -> !currentClientUuid.equals(c.getId())) // Not the one currently in use
                .forEach(oldClient -> {
                    log.infof("Deleting orphaned DCR client %s for user %s", oldClient.getClientId(), userId);
                    session.clients().removeClient(realm, oldClient.getId());
                });
    }

    /**
     * Handles Keycloak Admin events.
     * Specifically looks for Client CREATE events to tag the newly created DCR client
     * with its creation timestamp and deterministic fingerprint.
     *
     * @param event               The triggered Admin event.
     * @param includeRepresentation Whether the event includes the resource representation.
     */
    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // We only care about Client Creation
        if (event.getResourceType() != ResourceType.CLIENT || event.getOperationType() != OperationType.CREATE) {
            return;
        }

        // Only handle DCR (Dynamic Client Registration usually happens without an admin user in context,
        // or through specific endpoints, but we'll tag it if we can parse the representation)
        String clientUuid = event.getResourcePath().replace("clients/", "");
        RealmModel realm = session.realms().getRealm(event.getRealmId());
        
        if (realm == null || clientUuid == null || clientUuid.isEmpty()) {
            return;
        }

        ClientModel clientModel = session.clients().getClientById(realm, clientUuid);
        if (clientModel == null) {
            return;
        }

        try {
            // Try to extract representation to calculate fingerprint
            String fingerprint = calculateFingerprint(clientModel);
            
            // Tag the client
            long now = System.currentTimeMillis();
            clientModel.setAttribute(ATTR_DCR_CREATED_AT, String.valueOf(now));
            clientModel.setAttribute(ATTR_DCR_FINGERPRINT, fingerprint);
            
            log.infof("Tagged new DCR Client %s with fingerprint: %s", clientModel.getClientId(), fingerprint);
            
        } catch (Exception e) {
            log.error("Failed to process AdminEvent for new DCR client", e);
        }
    }

    /**
     * Calculates a deterministic fingerprint based on the client's redirect URIs and name.
     * Extracts scheme and host from each URI, sorts them, and concatenates with the client name.
     *
     * @param client The Keycloak ClientModel.
     * @return A fingerprint string uniquely identifying the client's origins.
     */
    // Visibility changed to package-private for testing
    String calculateFingerprint(ClientModel client) {
        Set<String> schemeHosts = new HashSet<>();
        
        Set<String> redirectUris = client.getRedirectUris();
        if (redirectUris != null) {
            for (String uriString : redirectUris) {
                try {
                    // Ignore wildcards or strictly invalid URIs for fingerprinting
                    if (uriString.equals("*")) continue;
                    
                    URI uri = new URI(uriString);
                    String scheme = uri.getScheme() != null ? uri.getScheme() : "none";
                    String host = uri.getHost() != null ? uri.getHost() : "none";
                    schemeHosts.add(scheme + "://" + host);
                } catch (Exception e) {
                    // Ignore unparseable URIs
                    log.debugf("Could not parse redirect URI for fingerprinting: %s", uriString);
                }
            }
        }

        // Sort alphabetically and join
        String sortedUris = schemeHosts.stream()
                .sorted()
                .collect(Collectors.joining(","));

        String clientName = client.getName() != null ? client.getName() : "Unknown";
        
        return sortedUris + "|" + clientName;
    }

    @Override
    public void close() {
        // Nothing to close
    }
}
