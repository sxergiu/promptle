package com.app.promptle.room.web;

import com.app.promptle.room.service.DisconnectGraceService;
import com.app.promptle.room.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketSessionListenerTest {

    @Mock
    private RoomService roomService;

    @Mock
    private DisconnectGraceService disconnectGraceService;

    private WebSocketSessionListener listener;

    @BeforeEach
    void setUp() {
        listener = new WebSocketSessionListener(roomService, disconnectGraceService);
    }

    private Message<byte[]> messageWithRoom(String roomCode) {
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("roomCode", roomCode);

        @SuppressWarnings("unchecked")
        Message<byte[]> message = mock(Message.class);
        org.springframework.messaging.MessageHeaders headers =
                new org.springframework.messaging.MessageHeaders(Map.of(
                        SimpMessageHeaderAccessor.SESSION_ATTRIBUTES, sessionAttributes
                ));
        when(message.getHeaders()).thenReturn(headers);
        return message;
    }

    @Test
    void handleDisconnect_DefersThroughGraceServiceInsteadOfDisconnectingImmediately() {
        UUID playerId = UUID.randomUUID();
        String roomCode = "ABCD1234";
        Principal principal = () -> playerId.toString();

        SessionDisconnectEvent event = new SessionDisconnectEvent(
                new Object(), messageWithRoom(roomCode), "session-1",
                org.springframework.web.socket.CloseStatus.NORMAL, principal
        );

        listener.handleDisconnect(event);

        verify(disconnectGraceService).scheduleDisconnect(roomCode, playerId);
        verify(roomService, never()).playerDisconnected(any(), any());
    }

    @Test
    void handleConnect_CancelsPendingDisconnectThenConnects() {
        UUID playerId = UUID.randomUUID();
        String roomCode = "ABCD1234";
        Principal principal = () -> playerId.toString();

        SessionConnectEvent event = new SessionConnectEvent(
                new Object(), messageWithRoom(roomCode), principal
        );

        listener.handleConnect(event);

        verify(disconnectGraceService).cancelPending(playerId);
        verify(roomService).playerConnected(roomCode, playerId);
    }
}
