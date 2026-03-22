package com.app.promptle.game.broadcast;

import com.app.promptle.game.dto.*;
import com.app.promptle.game.event.*;
import com.app.promptle.room.event.RoomApplicationEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.Map;
import java.util.UUID;

/**
 * Stub — fully implemented in later chunks (Chunk 4, 8, 9, 12).
 * Broadcasts Spring application events as WebSocket messages.
 */
@Component
public class GameWebSocketBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public GameWebSocketBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoomEvent(RoomApplicationEvent event) {
        messagingTemplate.convertAndSend(
                "/topic/room/" + event.roomCode(),
                event.payload()
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPhaseChanged(PhaseChangedApplicationEvent event) {
        PhaseChangedEvent dto = new PhaseChangedEvent(
                event.phase(), event.round(), event.totalRounds(),
                event.timerSeconds(), event.serverTimestamp()
        );
        messagingTemplate.convertAndSend("/topic/game/" + event.roomCode(), dto);
    }

    public void onSubmissionUpdate(SubmissionUpdateApplicationEvent event) {
        SubmissionUpdateEvent dto = new SubmissionUpdateEvent(
                event.submittedCount(), event.totalCount()
        );
        messagingTemplate.convertAndSend("/topic/game/" + event.roomCode(), dto);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoundReady(RoundReadyApplicationEvent event) {
        for (Map.Entry<UUID, String> entry : event.playerImageUrls().entrySet()) {
            RoundReadyPayload payload = new RoundReadyPayload(0, entry.getValue());
            messagingTemplate.convertAndSendToUser(
                    entry.getKey().toString(),
                    "/queue/game",
                    payload
            );
        }
    }

    public void onGameResults(GameResultsApplicationEvent event) {
        messagingTemplate.convertAndSend("/topic/game/" + event.roomCode(), event.payload());
    }

    public void onShowcaseAdvanced(ShowcaseApplicationEvent event) {
        ShowcaseAdvancedEvent dto = new ShowcaseAdvancedEvent(event.chainIndex());
        messagingTemplate.convertAndSend("/topic/game/" + event.roomCode(), dto);
    }
}
