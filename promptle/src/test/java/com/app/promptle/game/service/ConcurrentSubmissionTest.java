package com.app.promptle.game.service;

import com.app.promptle.game.model.*;
import com.app.promptle.game.repository.*;
import com.app.promptle.image.api.ImageGenerationService;
import com.app.promptle.image.api.ImageStorageService;
import com.app.promptle.image.filter.PromptFilter;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import com.app.promptle.room.repository.PlayerRepository;
import com.app.promptle.room.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Concurrency tests for GameService — F-1 and F-2.
 *
 * These tests use an ExecutorService + CountDownLatch to simulate two players
 * submitting simultaneously and verify that endPromptingRound / endGuessingRound
 * is triggered exactly once (no double-transition).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConcurrentSubmissionTest {

    @Mock private RoomRepository roomRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private ChainRepository chainRepository;
    @Mock private ChainEntryRepository chainEntryRepository;
    @Mock private ArtStyleRepository artStyleRepository;
    @Mock private RoundAssignmentService roundAssignmentService;
    @Mock private TimerService timerService;
    @Mock private ImageGenerationService imageGenerationService;
    @Mock private ImageStorageService imageStorageService;
    @Mock private PromptFilter promptFilter;
    @Mock private ApplicationEventPublisher eventPublisher;

    private GameService gameService;

    private static final long PROMPTING_SECONDS = 60L;
    private static final long GUESSING_SECONDS = 45L;

    @BeforeEach
    void setUp() {
        when(promptFilter.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
        gameService = new GameService(
                roomRepository,
                playerRepository,
                chainRepository,
                chainEntryRepository,
                artStyleRepository,
                roundAssignmentService,
                timerService,
                imageGenerationService,
                imageStorageService,
                promptFilter,
                eventPublisher,
                PROMPTING_SECONDS,
                GUESSING_SECONDS
        );
    }

    // ---- F-1: Concurrent submitPrompt — endPromptingRound called exactly once ----

    @RepeatedTest(5)
    void submitPrompt_Concurrent_TwoPlayers_EndPromptingRoundCalledExactlyOnce() throws Exception {
        Room room = buildRoom("CONCURRENT1", GamePhase.PROMPTING);
        room.setCurrentRound(1);

        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        Player p1 = buildPlayer(p1Id, room);
        Player p2 = buildPlayer(p2Id, room);
        Chain c1 = buildChain(p1, room);
        Chain c2 = buildChain(p2, room);

        // Both players connected
        when(roomRepository.findByRoomCode("CONCURRENT1")).thenReturn(Optional.of(room));
        when(playerRepository.findById(p1Id)).thenReturn(Optional.of(p1));
        when(playerRepository.findById(p2Id)).thenReturn(Optional.of(p2));
        when(chainRepository.findByRoomAndOriginPlayer(room, p1)).thenReturn(Optional.of(c1));
        when(chainRepository.findByRoomAndOriginPlayer(room, p2)).thenReturn(Optional.of(c2));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(p1, p2));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(c1, c2));

        // Idempotency guard: neither player has submitted yet
        when(chainEntryRepository.existsByChainOriginPlayerAndRoundAndIsPlaceholderFalse(any(), eq(1)))
                .thenReturn(false);

        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));

        // After the FIRST save (one submission), count = 1 out of 2 — not all submitted yet.
        // After the SECOND save (two submissions), count = 2 out of 2 — all submitted.
        AtomicInteger saveCount = new AtomicInteger(0);
        when(chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(any(), anyInt()))
                .thenAnswer(inv -> (long) saveCount.incrementAndGet());

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            try {
                startLatch.await();
                gameService.submitPrompt("CONCURRENT1", p1Id, "Prompt from P1");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                gameService.submitPrompt("CONCURRENT1", p2Id, "Prompt from P2");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown(); // release both threads simultaneously
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Both threads must finish within 5 seconds");
        executor.shutdown();

        // endPromptingRound sets phase to GENERATING — assert this happened exactly once
        // (room.setPhase is called once; if there's a race, it might be called twice)
        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository, atLeastOnce()).save(roomCaptor.capture());

        long generatingTransitions = roomCaptor.getAllValues().stream()
                .filter(r -> r.getPhase() == GamePhase.GENERATING)
                .count();
        // The room must have transitioned to GENERATING exactly once
        assertEquals(1L, generatingTransitions,
                "Room must transition to GENERATING exactly once, regardless of concurrent submits");
    }

    // ---- F-2: Concurrent submitGuess — endGuessingRound called exactly once ----

    @RepeatedTest(5)
    void submitGuess_Concurrent_TwoPlayers_EndGuessingRoundCalledExactlyOnce() throws Exception {
        int totalRounds = 2;
        Room room = buildRoom("CONCURRENT2", GamePhase.GUESSING);
        room.setCurrentRound(totalRounds);
        room.setTotalRounds(totalRounds);

        UUID p1Id = UUID.randomUUID();
        UUID p2Id = UUID.randomUUID();
        Player p1 = buildPlayer(p1Id, room);
        Player p2 = buildPlayer(p2Id, room);
        Chain c1 = buildChain(p1, room);
        Chain c2 = buildChain(p2, room);

        when(roomRepository.findByRoomCode("CONCURRENT2")).thenReturn(Optional.of(room));
        when(playerRepository.findById(p1Id)).thenReturn(Optional.of(p1));
        when(playerRepository.findById(p2Id)).thenReturn(Optional.of(p2));
        when(roundAssignmentService.getAssignedChain(room, p1, totalRounds)).thenReturn(c2);
        when(roundAssignmentService.getAssignedChain(room, p2, totalRounds)).thenReturn(c1);
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(p1, p2));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(c1, c2));

        // Idempotency guard: neither player has submitted yet
        when(chainEntryRepository.existsByChainAndRoundAndAuthorAndIsPlaceholderFalse(any(), eq(totalRounds), any()))
                .thenReturn(false);

        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainEntryRepository.findByChainOrderByRoundAsc(any())).thenReturn(List.of());

        // After first save: 1 of 2; after second save: 2 of 2
        AtomicInteger saveCount = new AtomicInteger(0);
        when(chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(any(), anyInt()))
                .thenAnswer(inv -> (long) saveCount.incrementAndGet());

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            try {
                startLatch.await();
                gameService.submitGuess("CONCURRENT2", p1Id, "Guess from P1");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                gameService.submitGuess("CONCURRENT2", p2Id, "Guess from P2");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Both threads must finish within 5 seconds");
        executor.shutdown();

        // endGuessingRound on the final round sets phase to RESULTS exactly once
        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository, atLeastOnce()).save(roomCaptor.capture());

        long resultsTransitions = roomCaptor.getAllValues().stream()
                .filter(r -> r.getPhase() == GamePhase.RESULTS)
                .count();
        assertEquals(1L, resultsTransitions,
                "Room must transition to RESULTS exactly once, regardless of concurrent submits");
    }

    // ---- Helpers ----

    private Room buildRoom(String code, GamePhase phase) {
        Room room = new Room();
        room.setId(UUID.randomUUID());
        room.setRoomCode(code);
        room.setPhase(phase);
        room.setCurrentRound(0);
        room.setTotalRounds(0);
        room.setHostId(UUID.randomUUID());
        return room;
    }

    private Player buildPlayer(UUID id, Room room) {
        Player p = new Player();
        p.setId(id);
        p.setToken(UUID.randomUUID());
        p.setAlias("Player");
        p.setAvatarId("icon-1");
        p.setRoom(room);
        p.setConnected(true);
        p.setJoinedAt(Instant.now());
        return p;
    }

    private Chain buildChain(Player player, Room room) {
        Chain chain = new Chain();
        chain.setId(UUID.randomUUID());
        chain.setRoom(room);
        chain.setOriginPlayer(player);
        return chain;
    }
}
