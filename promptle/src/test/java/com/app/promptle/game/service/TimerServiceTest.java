package com.app.promptle.game.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TimerServiceTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private GameService gameService;

    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private TimerService timerService;

    @BeforeEach
    void setUp() {
        timerService = new TimerService(taskScheduler, gameService, 120L);
    }

    // ---- startRoundTimer ----

    @Test
    @SuppressWarnings("unchecked")
    void startRoundTimer_SchedulesTaskAtCorrectInstant_WithinTolerance() {
        long duration = 60L;
        Instant before = Instant.now().plusSeconds(duration);

        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn((ScheduledFuture) scheduledFuture);

        timerService.startRoundTimer("ABCD1234", 1, duration);

        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(any(Runnable.class), instantCaptor.capture());

        Instant captured = instantCaptor.getValue();
        Instant after = Instant.now().plusSeconds(duration);

        // Should be scheduled at approximately now + duration (within 5 seconds tolerance)
        assertFalse(captured.isBefore(before.minusSeconds(5)),
                "Scheduled time should be approximately now + " + duration);
        assertFalse(captured.isAfter(after.plusSeconds(5)),
                "Scheduled time should not be more than " + duration + " seconds in future");
    }

    @Test
    @SuppressWarnings("unchecked")
    void startRoundTimer_CallbackFiresCorrectly_WhenRunnableExecuted() {
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn((ScheduledFuture) scheduledFuture);

        timerService.startRoundTimer("ABCD1234", 2, 30L);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // Execute the captured runnable
        runnableCaptor.getValue().run();

        verify(gameService).onRoundTimerExpired("ABCD1234", 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startRoundTimer_StoresFutureForCancelation() {
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn((ScheduledFuture) scheduledFuture);

        timerService.startRoundTimer("ABCD1234", 1, 60L);
        timerService.cancelTimer("ABCD1234");

        verify(scheduledFuture).cancel(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startRoundTimer_OverwritesExistingFuture_OnlyLatestStoredAfterTwoCalls() {
        ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> secondFuture = mock(ScheduledFuture.class);

        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn((ScheduledFuture) firstFuture)
                .thenReturn((ScheduledFuture) secondFuture);

        timerService.startRoundTimer("ABCD1234", 1, 60L);
        timerService.startRoundTimer("ABCD1234", 2, 60L);
        timerService.cancelTimer("ABCD1234");

        verify(secondFuture).cancel(false);
        verify(firstFuture, never()).cancel(anyBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void startRoundTimer_UsesDurationFromArgument_NotHardcoded() {
        long customDuration = 120L;
        Instant before = Instant.now().plusSeconds(customDuration);

        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn((ScheduledFuture) scheduledFuture);

        timerService.startRoundTimer("ABCD1234", 1, customDuration);

        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(any(Runnable.class), instantCaptor.capture());

        Instant captured = instantCaptor.getValue();
        // Should be at least customDuration seconds from now, not shorter
        assertFalse(captured.isBefore(before.minusSeconds(3)),
                "Duration should match argument: " + customDuration + " seconds");
    }

    // ---- cancelTimer ----

    @Test
    @SuppressWarnings("unchecked")
    void cancelTimer_CancelsFutureWithFalseArgument() {
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn((ScheduledFuture) scheduledFuture);

        timerService.startRoundTimer("ABCD1234", 1, 60L);
        timerService.cancelTimer("ABCD1234");

        verify(scheduledFuture).cancel(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelTimer_RemovesEntry_SecondCallIsNoOpAndDoesNotCancelAgain() {
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn((ScheduledFuture) scheduledFuture);

        timerService.startRoundTimer("ABCD1234", 1, 60L);
        timerService.cancelTimer("ABCD1234");
        timerService.cancelTimer("ABCD1234"); // second call should be no-op

        verify(scheduledFuture, times(1)).cancel(false); // only once
    }

    @Test
    void cancelTimer_NoOpWhenNoTimerExists_DoesNotThrow() {
        assertDoesNotThrow(() -> timerService.cancelTimer("NONEXISTENT"));
    }
}
