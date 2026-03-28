package com.app.promptle.room.web;

import com.app.promptle.room.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
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

    private WebSocketSessionListener listener;

    @BeforeEach
    void setUp() {
        listener = new WebSocketSessionListener(roomService);
    }

    @Test
    void handleDisconnect_DelegatesToRoomServiceWithCorrectArguments() {
        UUID playerId = UUID.randomUUID();
        String roomCode = "ABCD1234";

        Principal principal = () -> playerId.toString();

        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("roomCode", roomCode);

        @SuppressWarnings("unchecked")
        Message<byte[]> message = mock(Message.class);
        org.springframework.messaging.MessageHeaders headers =
                new org.springframework.messaging.MessageHeaders(Map.of(
                        SimpMessageHeaderAccessor.SESSION_ATTRIBUTES, sessionAttributes
                ));
        when(message.getHeaders()).thenReturn(headers);

        SessionDisconnectEvent event = new SessionDisconnectEvent(
                new Object(), message, "session-1", org.springframework.web.socket.CloseStatus.NORMAL, principal
        );

        listener.handleDisconnect(event);

        verify(roomService).playerDisconnected(roomCode, playerId);
    }
}
