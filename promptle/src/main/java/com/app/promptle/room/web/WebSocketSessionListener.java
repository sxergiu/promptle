package com.app.promptle.room.web;

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
 * Stub — fully implemented in a later chunk.
 * Listens for WebSocket disconnect events and delegates to RoomService.
 */
@Component
public class WebSocketSessionListener {

    private final RoomService roomService;

    public WebSocketSessionListener(RoomService roomService) {
        this.roomService = roomService;
    }

    @EventListener
    public void handleConnect(SessionConnectEvent event) {
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
        roomService.playerDisconnected(roomCode, playerId);
    }
}
