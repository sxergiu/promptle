package com.app.promptle.game.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Stub — fully implemented in a later chunk.
 * Manages round timers for game phases.
 */
@Service
public class TimerService {

    private final TaskScheduler taskScheduler;
    private final GameService gameService;
    private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    public TimerService(TaskScheduler taskScheduler, @Lazy GameService gameService) {
        this.taskScheduler = taskScheduler;
        this.gameService = gameService;
    }

    /**
     * Starts a round timer that calls GameService.onRoundTimerExpired after durationSeconds.
     */
    public void startRoundTimer(String roomCode, int round, long durationSeconds) {
        Instant fireAt = Instant.now().plusSeconds(durationSeconds);
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> gameService.onRoundTimerExpired(roomCode, round),
                fireAt
        );
        timers.put(roomCode, future);
    }

    /**
     * Cancels any active timer for the given room.
     */
    public void cancelTimer(String roomCode) {
        ScheduledFuture<?> future = timers.remove(roomCode);
        if (future != null) {
            future.cancel(false);
        }
    }
}
