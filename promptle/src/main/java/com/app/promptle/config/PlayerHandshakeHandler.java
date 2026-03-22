package com.app.promptle.config;

import com.app.promptle.room.model.Player;
import com.app.promptle.room.repository.PlayerRepository;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stub — fully implemented in a later chunk.
 * Reads player token from the WS handshake URL and sets Principal to playerId.
 */
@Component
public class PlayerHandshakeHandler extends DefaultHandshakeHandler {

    private final PlayerRepository playerRepository;

    public PlayerHandshakeHandler(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                       WebSocketHandler wsHandler,
                                       Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (query == null) {
            return null;
        }
        String token = null;
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                token = param.substring("token=".length());
                break;
            }
        }
        if (token == null) {
            return null;
        }
        try {
            UUID tokenUuid = UUID.fromString(token);
            Optional<Player> playerOpt = playerRepository.findByToken(tokenUuid);
            if (playerOpt.isEmpty()) {
                return null;
            }
            final String playerIdStr = playerOpt.get().getId().toString();
            return () -> playerIdStr;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
