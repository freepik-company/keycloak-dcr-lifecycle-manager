package com.achetronic.keycloak.dcrlifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DcrLifecycleEventListenerProvider.
 * Validates that the fingerprint generation is deterministic, ignores wildcards
 * and properly hashes inputs using SHA-256.
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
}
