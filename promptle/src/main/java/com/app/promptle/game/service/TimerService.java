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
     * Starts a safety timeout for the GENERATING phase.
     * If image generation has not completed after 30 seconds, forces onAllImagesReady
     * which is idempotent — if generation already completed normally this is a no-op.
     */
    public void startGeneratingTimeout(String roomCode, int round) {
        Instant fireAt = Instant.now().plusSeconds(30);
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> gameService.onAllImagesReady(roomCode),
                fireAt
        );
        timers.put(roomCode + ":gen", future);
    }

    /**
     * Cancels the round timer (PROMPTING or GUESSING) for the given room.
     */
    public void cancelTimer(String roomCode) {
        ScheduledFuture<?> future = timers.remove(roomCode);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Cancels the GENERATING safety timeout for the given room.
     */
    public void cancelGeneratingTimeout(String roomCode) {
        ScheduledFuture<?> future = timers.remove(roomCode + ":gen");
        if (future != null) {
            future.cancel(false);
        }
    }
}
