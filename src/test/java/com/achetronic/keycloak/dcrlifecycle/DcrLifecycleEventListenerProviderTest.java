package com.achetronic.keycloak.dcrlifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DcrLifecycleEventListenerProvider.
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
        // Arrange
        ClientModel clientMock = Mockito.mock(ClientModel.class);
        when(clientMock.getName()).thenReturn("TestClient");
        
        Set<String> uris = new HashSet<>();
        uris.add("https://claude.ai/callback");
        uris.add("https://chat.openai.com/auth");
        when(clientMock.getRedirectUris()).thenReturn(uris);

        // Act
        String fingerprint = provider.calculateFingerprint(clientMock);

        // Assert
        // Expected sorted scheme://host combination
        String expected = "https://chat.openai.com,https://claude.ai|TestClient";
        assertEquals(expected, fingerprint);
    }

    @Test
    void testCalculateFingerprint_withCustomSchemes() {
        // Arrange
        ClientModel clientMock = Mockito.mock(ClientModel.class);
        when(clientMock.getName()).thenReturn("DesktopApp");
        
        Set<String> uris = new HashSet<>();
        uris.add("cursor://auth/callback");
        uris.add("http://localhost:8080/callback");
        when(clientMock.getRedirectUris()).thenReturn(uris);

        // Act
        String fingerprint = provider.calculateFingerprint(clientMock);

        // Assert
        String expected = "cursor://auth,http://localhost|DesktopApp";
        assertEquals(expected, fingerprint);
    }

    @Test
    void testCalculateFingerprint_withWildcardsAndInvalid() {
        // Arrange
        ClientModel clientMock = Mockito.mock(ClientModel.class);
        when(clientMock.getName()).thenReturn("WildcardApp");
        
        Set<String> uris = new HashSet<>();
        uris.add("*"); // Should be ignored
        uris.add("https://valid.com/cb");
        when(clientMock.getRedirectUris()).thenReturn(uris);

        // Act
        String fingerprint = provider.calculateFingerprint(clientMock);

        // Assert
        String expected = "https://valid.com|WildcardApp";
        assertEquals(expected, fingerprint);
    }

    @Test
    void testCalculateFingerprint_noUris() {
        // Arrange
        ClientModel clientMock = Mockito.mock(ClientModel.class);
        when(clientMock.getName()).thenReturn("NoUriApp");
        when(clientMock.getRedirectUris()).thenReturn(new HashSet<>());

        // Act
        String fingerprint = provider.calculateFingerprint(clientMock);

        // Assert
        String expected = "|NoUriApp";
        assertEquals(expected, fingerprint);
    }
}
