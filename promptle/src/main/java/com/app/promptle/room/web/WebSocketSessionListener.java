package com.app.promptle.room.web;

import com.app.promptle.room.service.DisconnectGraceService;
import com.app.promptle.room.service.RoomService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Listens for WebSocket connect/disconnect events. Connects apply immediately;
 * disconnects are deferred through {@link DisconnectGraceService} so a brief
 * mobile background doesn't make a player (or the host) vanish from the room.
 */
@Component
public class WebSocketSessionListener {

    private final RoomService roomService;
    private final DisconnectGraceService disconnectGraceService;

    public WebSocketSessionListener(RoomService roomService, DisconnectGraceService disconnectGraceService) {
        this.roomService = roomService;
        this.disconnectGraceService = disconnectGraceService;
    }

    @EventListener
    public void handleConnect(SessionConnectEvent event) {
        Principal principal = event.getUser();
        if (principal == null) {
            return;
        }
        UUID playerId = UUID.fromString(principal.getName());

        // Cancel any disconnect still inside its grace window — the player is back.
        disconnectGraceService.cancelPending(playerId);

        Map<String, Object> sessionAttributes = SimpMessageHeaderAccessor
                .getSessionAttributes(event.getMessage().getHeaders());
        if (sessionAttributes == null) {
            return;
        }
        String roomCode = (String) sessionAttributes.get("roomCode");
        if (roomCode == null) {
            return;
        }
        roomService.playerConnected(roomCode, playerId);
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        if (principal == null) {
            return;
        }
        UUID playerId = UUID.fromString(principal.getName());

        Map<String, Object> sessionAttributes = SimpMessageHeaderAccessor
                .getSessionAttributes(event.getMessage().getHeaders());
        if (sessionAttributes == null) {
            return;
        }
        String roomCode = (String) sessionAttributes.get("roomCode");
        if (roomCode == null) {
            return;
        }
        // Defer the real disconnect — a brief background (e.g. opening the share
        // sheet) reconnects within the grace window and cancels this.
        disconnectGraceService.scheduleDisconnect(roomCode, playerId);
    }
}
