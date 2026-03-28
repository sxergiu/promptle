package com.app.promptle.config;

import com.app.promptle.room.model.Player;
import com.app.promptle.room.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlayerHandshakeHandlerTest {

    @Mock
    private PlayerRepository playerRepository;

    @Mock
    private ServerHttpRequest request;

    @Mock
    private WebSocketHandler wsHandler;

    private PlayerHandshakeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PlayerHandshakeHandler(playerRepository);
    }

    @Test
    void determineUser_KnownToken_ReturnsPrincipalWithPlayerIdAsName() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        Player player = new Player();
        player.setId(playerId);
        player.setToken(token);

        when(playerRepository.findByToken(token)).thenReturn(Optional.of(player));
        when(request.getURI()).thenReturn(new URI("/ws?token=" + token + "&roomCode=ABCD1234"));

        Map<String, Object> attributes = new HashMap<>();
        Principal principal = handler.determineUser(request, wsHandler, attributes);

        assertNotNull(principal);
        assertEquals(playerId.toString(), principal.getName());
    }

    @Test
    void determineUser_UnknownToken_ReturnsNull() throws Exception {
        UUID token = UUID.randomUUID();

        when(playerRepository.findByToken(token)).thenReturn(Optional.empty());
        when(request.getURI()).thenReturn(new URI("/ws?token=" + token + "&roomCode=ABCD1234"));

        Map<String, Object> attributes = new HashMap<>();
        Principal principal = handler.determineUser(request, wsHandler, attributes);

        assertNull(principal);
    }
}
