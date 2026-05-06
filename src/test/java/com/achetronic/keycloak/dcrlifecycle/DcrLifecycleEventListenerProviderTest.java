package com.achetronic.keycloak.dcrlifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the fingerprint generation logic of
 * {@link DcrLifecycleEventListenerProvider}.
 */
class DcrLifecycleEventListenerProviderTest {

    private DcrLifecycleEventListenerProvider provider;
    private KeycloakSession sessionMock;

    @BeforeEach
    void setUp() {
        sessionMock = Mockito.mock(KeycloakSession.class);
        provider = new DcrLifecycleEventListenerProvider(sessionMock);
    }

    @Test
    void testCalculateFingerprint_withStandardUris() {
        ClientModel clientMock = Mockito.mock(ClientModel.class);
        when(clientMock.getName()).thenReturn("TestClient");

        Set<String> uris = new HashSet<>();
        uris.add("https://claude.ai/callback");
        uris.add("https://chat.openai.com/auth");
        when(clientMock.getRedirectUris()).thenReturn(uris);

        String fingerprint = provider.calculateFingerprint(clientMock);

        String expected = sha256Hex("https://chat.openai.com,https://claude.ai|TestClient");
        assertEquals(expected, fingerprint);
    }

    @Test
    void testCalculateFingerprint_withCustomSchemes() {
        ClientModel clientMock = Mockito.mock(ClientModel.class);
        when(clientMock.getName()).thenReturn("DesktopApp");

        Set<String> uris = new HashSet<>();
        uris.add("cursor://auth/callback");
        uris.add("http://localhost:8080/callback");
        when(clientMock.getRedirectUris()).thenReturn(uris);

        String fingerprint = provider.calculateFingerprint(clientMock);

        String expected = sha256Hex("cursor://auth,http://localhost|DesktopApp");
        assertEquals(expected, fingerprint);
    }

    @Test
    void testCalculateFingerprint_withWildcardsAndInvalid() {
        ClientModel clientMock = Mockito.mock(ClientModel.class);
        when(clientMock.getName()).thenReturn("WildcardApp");

        Set<String> uris = new HashSet<>();
        uris.add("*"); // Should be ignored
        uris.add("https://valid.com/cb");
        when(clientMock.getRedirectUris()).thenReturn(uris);

        String fingerprint = provider.calculateFingerprint(clientMock);

        String expected = sha256Hex("https://valid.com|WildcardApp");
        assertEquals(expected, fingerprint);
    }

    @Test
    void testCalculateFingerprint_noUris() {
        ClientModel clientMock = Mockito.mock(ClientModel.class);
        when(clientMock.getName()).thenReturn("NoUriApp");
        when(clientMock.getRedirectUris()).thenReturn(new HashSet<>());

        String fingerprint = provider.calculateFingerprint(clientMock);

        String expected = sha256Hex("|NoUriApp");
        assertEquals(expected, fingerprint);
    }

    @Test
    void testCalculateFingerprint_isDeterministic() {
        ClientModel clientMock = Mockito.mock(ClientModel.class);
        when(clientMock.getName()).thenReturn("Claude");
        Set<String> uris = new HashSet<>();
        uris.add("https://claude.ai/cb");
        when(clientMock.getRedirectUris()).thenReturn(uris);

        String fingerprint1 = provider.calculateFingerprint(clientMock);
        String fingerprint2 = provider.calculateFingerprint(clientMock);
        assertEquals(fingerprint1, fingerprint2);
    }

    @Test
    void testCalculateFingerprint_differentClientsProduceDifferentHashes() {
        ClientModel claude = Mockito.mock(ClientModel.class);
        when(claude.getName()).thenReturn("Claude");
        Set<String> claudeUris = new HashSet<>();
        claudeUris.add("https://claude.ai/cb");
        when(claude.getRedirectUris()).thenReturn(claudeUris);

        ClientModel chatgpt = Mockito.mock(ClientModel.class);
        when(chatgpt.getName()).thenReturn("ChatGPT");
        Set<String> chatgptUris = new HashSet<>();
        chatgptUris.add("https://chatgpt.com/cb");
        when(chatgpt.getRedirectUris()).thenReturn(chatgptUris);

        assertNotEquals(provider.calculateFingerprint(claude), provider.calculateFingerprint(chatgpt));
    }

    /**
     * Helper that mirrors the SHA-256 hex-encoding implemented in the production code,
     * to verify the fingerprint output without relying on hardcoded hashes.
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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Event handler tests (CLIENT_REGISTER + LOGIN)
    // -------------------------------------------------------------------------

    /**
     * Wires up a fully mocked Keycloak session so that
     * {@code session.realms().getRealm(realmId)} returns a realm and
     * {@code session.clients().getClientByClientId(realm, clientId)} returns the given client.
     */
    private void wireSession(String realmId, String clientId, RealmModel realm, ClientModel client) {
        RealmProvider realmProvider = Mockito.mock(RealmProvider.class);
        ClientProvider clientProvider = Mockito.mock(ClientProvider.class);
        when(sessionMock.realms()).thenReturn(realmProvider);
        when(sessionMock.clients()).thenReturn(clientProvider);
        when(realmProvider.getRealm(realmId)).thenReturn(realm);
        when(clientProvider.getClientByClientId(realm, clientId)).thenReturn(client);
    }

    @Test
    void onEvent_ignoresEventsWithError() {
        Event event = Mockito.mock(Event.class);
        when(event.getError()).thenReturn("some_error");

        provider.onEvent(event);

        // No interaction with realms/clients providers expected.
        verify(sessionMock, never()).realms();
        verify(sessionMock, never()).clients();
    }

    @Test
    void onEvent_ignoresUnrelatedEventTypes() {
        Event event = Mockito.mock(Event.class);
        when(event.getType()).thenReturn(EventType.LOGOUT);

        provider.onEvent(event);

        verify(sessionMock, never()).realms();
        verify(sessionMock, never()).clients();
    }

    @Test
    void clientRegister_writesAllThreeAttributes() {
        ClientModel client = Mockito.mock(ClientModel.class);
        when(client.getClientId()).thenReturn("claude-1");
        when(client.getName()).thenReturn("Claude");
        Set<String> uris = new HashSet<>();
        uris.add("https://claude.ai/cb");
        when(client.getRedirectUris()).thenReturn(uris);

        RealmModel realm = Mockito.mock(RealmModel.class);
        wireSession("master", "claude-1", realm, client);

        Event event = Mockito.mock(Event.class);
        when(event.getType()).thenReturn(EventType.CLIENT_REGISTER);
        when(event.getRealmId()).thenReturn("master");
        when(event.getClientId()).thenReturn("claude-1");

        provider.onEvent(event);

        verify(client).setAttribute(DcrLifecycleEventListenerProvider.ATTR_IS_DCR_CLIENT, "true");
        verify(client).setAttribute(Mockito.eq(DcrLifecycleEventListenerProvider.ATTR_CREATED_AT), anyString());
        verify(client).setAttribute(Mockito.eq(DcrLifecycleEventListenerProvider.ATTR_FINGERPRINT), anyString());
    }

    @Test
    void clientRegister_doesNotTagWhenClientNotFound() {
        RealmModel realm = Mockito.mock(RealmModel.class);
        wireSession("master", "unknown-client", realm, null);

        Event event = Mockito.mock(Event.class);
        when(event.getType()).thenReturn(EventType.CLIENT_REGISTER);
        when(event.getRealmId()).thenReturn("master");
        when(event.getClientId()).thenReturn("unknown-client");

        provider.onEvent(event);
        // No NPE, no tagging. The realms()/clients() mocks were called but no setAttribute happened.
    }

    @Test
    void login_marksDcrClientWithLinkedUserAndUsedAt() {
        ClientModel client = Mockito.mock(ClientModel.class);
        when(client.getClientId()).thenReturn("claude-1");
        // Tagged as DCR client
        when(client.getAttribute(DcrLifecycleEventListenerProvider.ATTR_IS_DCR_CLIENT)).thenReturn("true");

        RealmModel realm = Mockito.mock(RealmModel.class);
        wireSession("master", "claude-1", realm, client);

        Event event = Mockito.mock(Event.class);
        when(event.getType()).thenReturn(EventType.LOGIN);
        when(event.getRealmId()).thenReturn("master");
        when(event.getClientId()).thenReturn("claude-1");
        when(event.getUserId()).thenReturn("user-uuid");

        provider.onEvent(event);

        verify(client).setAttribute(DcrLifecycleEventListenerProvider.ATTR_LINKED_USER_ID, "user-uuid");
        verify(client).setAttribute(Mockito.eq(DcrLifecycleEventListenerProvider.ATTR_USED_AT), anyString());
        // Hot path must NOT trigger any other client manipulation.
        verify(client, never()).setAttribute(
                Mockito.eq(DcrLifecycleEventListenerProvider.ATTR_CREATED_AT), anyString());
        verify(client, never()).setAttribute(
                Mockito.eq(DcrLifecycleEventListenerProvider.ATTR_FINGERPRINT), anyString());
    }

    @Test
    void login_ignoresNonDcrClient() {
        ClientModel client = Mockito.mock(ClientModel.class);
        when(client.getClientId()).thenReturn("manual-client");
        // NOT tagged as DCR client
        when(client.getAttribute(DcrLifecycleEventListenerProvider.ATTR_IS_DCR_CLIENT)).thenReturn(null);

        RealmModel realm = Mockito.mock(RealmModel.class);
        wireSession("master", "manual-client", realm, client);

        Event event = Mockito.mock(Event.class);
        when(event.getType()).thenReturn(EventType.LOGIN);
        when(event.getRealmId()).thenReturn("master");
        when(event.getClientId()).thenReturn("manual-client");
        when(event.getUserId()).thenReturn("user-uuid");

        provider.onEvent(event);

        // No tagging at all on manual clients.
        verify(client, never()).setAttribute(anyString(), anyString());
    }

    @Test
    void login_ignoresEventWithMissingFields() {
        Event event = Mockito.mock(Event.class);
        when(event.getType()).thenReturn(EventType.LOGIN);
        when(event.getRealmId()).thenReturn("master");
        when(event.getClientId()).thenReturn(null); // missing
        when(event.getUserId()).thenReturn("user-uuid");

        provider.onEvent(event);

        // Not even reaching the realm lookup.
        verify(sessionMock, never()).realms();
    }
}
