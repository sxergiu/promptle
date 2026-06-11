package com.app.promptle.room.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Debounces WebSocket disconnects.
 *
 * <p>A mobile client briefly backgrounding the tab — most commonly a host opening
 * the OS share sheet to send the invite — drops its socket instantly. Acting on
 * that immediately would mark the player (and possibly the host) gone: they vanish
 * from the roster for anyone joining, a premature host hand-off fires, and the
 * room can even auto-reset. Instead we defer the real disconnect by a short grace
 * window; if the same player reconnects within it, the pending disconnect is
 * cancelled and nothing ever changed.
 */
@Service
public class DisconnectGraceService {

    private static final Logger log = LoggerFactory.getLogger(DisconnectGraceService.class);

    private final TaskScheduler taskScheduler;
    private final RoomService roomService;
    private final long graceSeconds;
    private final Map<UUID, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    public DisconnectGraceService(TaskScheduler taskScheduler, RoomService roomService,
                                  @Value("${game.disconnect.grace-seconds:10}") long graceSeconds) {
        this.taskScheduler = taskScheduler;
        this.roomService = roomService;
        this.graceSeconds = graceSeconds;
    }

    /** Schedule the real disconnect after the grace window, replacing any pending one for this player. */
    public void scheduleDisconnect(String roomCode, UUID playerId) {
        cancelPending(playerId);
        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            pending.remove(playerId);
            try {
                roomService.playerDisconnected(roomCode, playerId);
            } catch (RuntimeException e) {
                // Player already gone (room reset, ghost-cleaned, etc.) — nothing left to do.
                log.debug("Deferred disconnect for player {} in room {} was a no-op: {}", playerId, roomCode, e.getMessage());
            }
        }, Instant.now().plusSeconds(graceSeconds));
        pending.put(playerId, future);
    }

    /** Cancel a pending disconnect — the player reconnected within the grace window. */
    public void cancelPending(UUID playerId) {
        ScheduledFuture<?> future = pending.remove(playerId);
        if (future != null) {
            future.cancel(false);
        }
    }
}
