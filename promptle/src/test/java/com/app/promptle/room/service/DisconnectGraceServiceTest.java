package com.app.promptle.room.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisconnectGraceServiceTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private RoomService roomService;

    @Mock
    private ScheduledFuture<Object> future;

    private DisconnectGraceService service;

    private static final long GRACE = 10L;

    @BeforeEach
    void setUp() {
        service = new DisconnectGraceService(taskScheduler, roomService, GRACE);
    }

    @Test
    void scheduleDisconnect_RunningTheTask_DelegatesToRoomService() {
        UUID playerId = UUID.randomUUID();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(inv -> future);

        service.scheduleDisconnect("ROOM1234", playerId);

        // Capture and run the scheduled task — it should perform the real disconnect.
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(Instant.class));
        task.getValue().run();

        verify(roomService).playerDisconnected("ROOM1234", playerId);
    }

    @Test
    void cancelPending_CancelsAScheduledDisconnect() {
        UUID playerId = UUID.randomUUID();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(inv -> future);

        service.scheduleDisconnect("ROOM1234", playerId);
        service.cancelPending(playerId);

        verify(future).cancel(false);
    }

    @Test
    void scheduleDisconnect_Twice_CancelsTheFirstPendingDisconnect() {
        UUID playerId = UUID.randomUUID();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(inv -> future);

        service.scheduleDisconnect("ROOM1234", playerId);
        service.scheduleDisconnect("ROOM1234", playerId);

        // The first future is superseded and must be cancelled.
        verify(future).cancel(false);
    }

    @Test
    void scheduledTask_SwallowsExceptions_WhenPlayerAlreadyGone() {
        UUID playerId = UUID.randomUUID();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(inv -> future);
        doThrow(new RuntimeException("Player not found")).when(roomService).playerDisconnected(any(), any());

        service.scheduleDisconnect("ROOM1234", playerId);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(Instant.class));

        // Must not propagate — a stale deferred disconnect is a no-op, not a crash.
        task.getValue().run();
    }
}
