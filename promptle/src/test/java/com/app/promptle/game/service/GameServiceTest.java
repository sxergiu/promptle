package com.app.promptle.game.service;

import com.app.promptle.common.exception.GameException;
import com.app.promptle.game.dto.*;
import com.app.promptle.game.event.*;
import com.app.promptle.game.model.*;
import com.app.promptle.game.repository.*;
import com.app.promptle.image.api.ImageGenerationService;
import com.app.promptle.image.api.ImageStorageService;
import com.app.promptle.image.filter.PromptFilter;
import com.app.promptle.room.event.RoomApplicationEvent;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import com.app.promptle.room.repository.PlayerRepository;
import com.app.promptle.room.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GameServiceTest {

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

    // Timer config values
    private static final long PROMPTING_SECONDS = 60L;
    private static final long GUESSING_SECONDS = 45L;

    @BeforeEach
    void setUp() {
        when(promptFilter.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
        when(artStyleRepository.findAll()).thenReturn(buildArtStyles(12));
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

    // ---- startGame ----

    @Test
    void startGame_ThrowsGameException_WhenNotHost() {
        UUID hostId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));

        assertThrows(GameException.class, () -> gameService.startGame("ABCD1234", otherId));
    }

    @Test
    void startGame_ThrowsGameException_WhenFewerThan2Players() {
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(buildPlayer(hostId, room)));

        assertThrows(GameException.class, () -> gameService.startGame("ABCD1234", hostId));
    }

    @Test
    void startGame_ThrowsGameException_WhenMoreThan8Players() {
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        List<Player> ninePlayers = buildPlayers(9, room);
        ninePlayers.get(0).setId(hostId);
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(ninePlayers);

        assertThrows(GameException.class, () -> gameService.startGame("ABCD1234", hostId));
    }

    @Test
    void startGame_SetsPhaseAndRoundsAndSavesRoom() {
        int n = 4;
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        List<Player> players = buildPlayers(n, room);
        players.get(0).setId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(players);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.save(any(Chain.class))).thenAnswer(inv -> {
            Chain c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        gameService.startGame("ABCD1234", hostId);

        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository, atLeastOnce()).save(roomCaptor.capture());
        Room saved = roomCaptor.getAllValues().stream()
                .filter(r -> r.getPhase() == GamePhase.PROMPTING)
                .findFirst().orElseThrow();
        assertEquals(GamePhase.PROMPTING, saved.getPhase());
        assertEquals(1, saved.getCurrentRound());
        assertEquals(n, saved.getTotalRounds());
    }

    @Test
    void startGame_CreatesExactlyOneChainPerPlayer() {
        int n = 3;
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        List<Player> players = buildPlayers(n, room);
        players.get(0).setId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(players);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.save(any(Chain.class))).thenAnswer(inv -> {
            Chain c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        gameService.startGame("ABCD1234", hostId);

        verify(chainRepository, times(n)).save(any(Chain.class));
    }

    @Test
    void startGame_CallsRoundAssignmentServiceGenerateAssignments() {
        int n = 4;
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        List<Player> players = buildPlayers(n, room);
        players.get(0).setId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(players);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.save(any(Chain.class))).thenAnswer(inv -> {
            Chain c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        gameService.startGame("ABCD1234", hostId);

        verify(roundAssignmentService).generateAssignments(eq(room), anyList(), anyList());
    }

    @Test
    void startGame_StartsRoundTimerForRound1() {
        int n = 2;
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        List<Player> players = buildPlayers(n, room);
        players.get(0).setId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(players);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.save(any(Chain.class))).thenAnswer(inv -> {
            Chain c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        gameService.startGame("ABCD1234", hostId);

        verify(timerService).startRoundTimer("ABCD1234", 1, PROMPTING_SECONDS);
    }

    @Test
    void startGame_PublishesGameStartedRoomEvent() {
        int n = 2;
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        List<Player> players = buildPlayers(n, room);
        players.get(0).setId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(players);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.save(any(Chain.class))).thenAnswer(inv -> {
            Chain c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        gameService.startGame("ABCD1234", hostId);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        boolean hasGameStarted = captor.getAllValues().stream()
                .filter(e -> e instanceof RoomApplicationEvent)
                .map(e -> (RoomApplicationEvent) e)
                .anyMatch(e -> e.payload() != null &&
                        "GAME_STARTED".equals(e.payload().type() != null ? e.payload().type().toString() : null));
        assertTrue(hasGameStarted);
    }

    @Test
    void startGame_PublishesPhaseChangedEventWithPromptingPhase() {
        int n = 2;
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        List<Player> players = buildPlayers(n, room);
        players.get(0).setId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(players);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.save(any(Chain.class))).thenAnswer(inv -> {
            Chain c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        gameService.startGame("ABCD1234", hostId);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        Optional<PhaseChangedApplicationEvent> phaseEvent = captor.getAllValues().stream()
                .filter(e -> e instanceof PhaseChangedApplicationEvent)
                .map(e -> (PhaseChangedApplicationEvent) e)
                .filter(e -> e.phase() == GamePhase.PROMPTING)
                .findFirst();
        assertTrue(phaseEvent.isPresent());
        assertEquals(1, phaseEvent.get().round());
        assertEquals(PROMPTING_SECONDS, phaseEvent.get().timerSeconds());
    }

    @Test
    void startGame_SetsRoundStartedAt_ToCurrentInstant() {
        int n = 2;
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        List<Player> players = buildPlayers(n, room);
        players.get(0).setId(hostId);

        Instant before = Instant.now();

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(players);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.save(any(Chain.class))).thenAnswer(inv -> {
            Chain c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        gameService.startGame("ABCD1234", hostId);

        Instant after = Instant.now();
        assertNotNull(room.getRoundStartedAt());
        assertFalse(room.getRoundStartedAt().isBefore(before.minusSeconds(2)));
        assertFalse(room.getRoundStartedAt().isAfter(after.plusSeconds(2)));
    }

    // ---- submitPrompt ----

    @Test
    void submitPrompt_SavesChainEntryWithCorrectFields() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        room.setTotalRounds(2);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain chain = buildChain(player, room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findById(playerId)).thenReturn(Optional.of(player));
        when(chainRepository.findByRoomAndOriginPlayer(room, player)).thenReturn(Optional.of(chain));
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainEntryRepository.findByChainAndRound(any(), anyInt())).thenReturn(Optional.empty());
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(any(), anyInt())).thenReturn(0L);

        gameService.submitPrompt("ABCD1234", playerId, "A beautiful sunset");

        ArgumentCaptor<ChainEntry> captor = ArgumentCaptor.forClass(ChainEntry.class);
        verify(chainEntryRepository).save(captor.capture());
        ChainEntry saved = captor.getValue();
        assertEquals(1, saved.getRound());
        assertEquals(player, saved.getAuthor());
        assertEquals("A beautiful sunset", saved.getText());
        assertFalse(saved.isPlaceholder());
    }

    @Test
    void submitPrompt_PublishesSubmissionUpdateEvent() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        room.setTotalRounds(2);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain chain = buildChain(player, room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findById(playerId)).thenReturn(Optional.of(player));
        when(chainRepository.findByRoomAndOriginPlayer(room, player)).thenReturn(Optional.of(chain));
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(any(), anyInt())).thenReturn(0L);

        gameService.submitPrompt("ABCD1234", playerId, "A prompt");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        boolean hasUpdate = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof SubmissionUpdateApplicationEvent);
        assertTrue(hasUpdate);
    }

    @Test
    void submitPrompt_AllSubmitted_CancelsTimerAndSetsGeneratingPhase() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        room.setTotalRounds(1);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain chain = buildChain(player, room);

        // Only one connected player, so 1 submission == all submitted
        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findById(playerId)).thenReturn(Optional.of(player));
        when(chainRepository.findByRoomAndOriginPlayer(room, player)).thenReturn(Optional.of(chain));
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        // After save, 1 submitted out of 1
        when(chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(any(), anyInt())).thenReturn(1L);
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));

        gameService.submitPrompt("ABCD1234", playerId, "A prompt");

        verify(timerService).cancelTimer("ABCD1234");
        assertEquals(GamePhase.GENERATING, room.getPhase());
    }

    @Test
    void submitPrompt_PartialSubmission_DoesNotCancelTimer() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        room.setTotalRounds(2);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain chain = buildChain(player, room);
        Player other = buildPlayer(UUID.randomUUID(), room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findById(playerId)).thenReturn(Optional.of(player));
        when(chainRepository.findByRoomAndOriginPlayer(room, player)).thenReturn(Optional.of(chain));
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player, other));
        // 1 out of 2 submitted
        when(chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(any(), anyInt())).thenReturn(1L);
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain, buildChain(other, room)));

        gameService.submitPrompt("ABCD1234", playerId, "A prompt");

        verify(timerService, never()).cancelTimer(anyString());
    }

    @Test
    void submitPrompt_StalePhaseGuard_IgnoresSubmission_WhenNotPrompting() {
        Room room = buildRoom("ABCD1234", GamePhase.GUESSING);
        room.setCurrentRound(2);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));

        gameService.submitPrompt("ABCD1234", UUID.randomUUID(), "late submission");

        verify(chainEntryRepository, never()).save(any(ChainEntry.class));
    }

    // ---- submitGuess ----

    @Test
    void submitGuess_SavesEntryOnAssignedChain() {
        Room room = buildRoom("ABCD1234", GamePhase.GUESSING);
        room.setCurrentRound(2);
        room.setTotalRounds(2);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain assignedChain = new Chain();
        assignedChain.setId(UUID.randomUUID());
        assignedChain.setRoom(room);
        Player chainOwner = buildPlayer(UUID.randomUUID(), room);
        assignedChain.setOriginPlayer(chainOwner);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findById(playerId)).thenReturn(Optional.of(player));
        when(roundAssignmentService.getAssignedChain(room, player, 2)).thenReturn(assignedChain);
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(assignedChain));
        when(chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(any(), anyInt())).thenReturn(0L);

        gameService.submitGuess("ABCD1234", playerId, "a guess");

        ArgumentCaptor<ChainEntry> captor = ArgumentCaptor.forClass(ChainEntry.class);
        verify(chainEntryRepository).save(captor.capture());
        assertEquals(assignedChain, captor.getValue().getChain());
        assertEquals(2, captor.getValue().getRound());
        assertFalse(captor.getValue().isPlaceholder());
    }

    @Test
    void submitGuess_PublishesSubmissionUpdateEvent() {
        Room room = buildRoom("ABCD1234", GamePhase.GUESSING);
        room.setCurrentRound(2);
        room.setTotalRounds(2);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain assignedChain = buildChain(buildPlayer(UUID.randomUUID(), room), room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findById(playerId)).thenReturn(Optional.of(player));
        when(roundAssignmentService.getAssignedChain(room, player, 2)).thenReturn(assignedChain);
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(assignedChain));
        when(chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(any(), anyInt())).thenReturn(0L);

        gameService.submitGuess("ABCD1234", playerId, "my guess");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(e -> e instanceof SubmissionUpdateApplicationEvent));
    }

    @Test
    void submitGuess_StalePhaseGuard_IgnoresSubmission_WhenNotGuessing() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));

        gameService.submitGuess("ABCD1234", UUID.randomUUID(), "a guess");

        verify(chainEntryRepository, never()).save(any(ChainEntry.class));
    }

    // ---- onRoundTimerExpired ----

    @Test
    void onRoundTimerExpired_PromptingWithMatch_CallsInsertPlaceholdersAndEndsRound() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain chain = buildChain(player, room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        // Player hasn't submitted
        when(chainEntryRepository.findByChainAndRound(chain, 1)).thenReturn(Optional.empty());
        when(chainRepository.findByRoomAndOriginPlayer(room, player)).thenReturn(Optional.of(chain));
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));

        gameService.onRoundTimerExpired("ABCD1234", 1);

        assertEquals(GamePhase.GENERATING, room.getPhase());
    }

    @Test
    void onRoundTimerExpired_DoesNothing_WhenRoundMismatch() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(2); // current round is 2

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));

        gameService.onRoundTimerExpired("ABCD1234", 1); // stale round 1 timer

        verify(chainEntryRepository, never()).save(any(ChainEntry.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---- insertPlaceholders ----

    @Test
    void insertPlaceholders_InsertsEntryWithCowText_ForNonSubmittedPlayer() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain chain = buildChain(player, room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainEntryRepository.findByChainAndRound(chain, 1)).thenReturn(Optional.empty());
        when(chainRepository.findByRoomAndOriginPlayer(room, player)).thenReturn(Optional.of(chain));

        ArgumentCaptor<ChainEntry> captor = ArgumentCaptor.forClass(ChainEntry.class);
        when(chainEntryRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));

        gameService.onRoundTimerExpired("ABCD1234", 1);

        List<ChainEntry> placeholders = captor.getAllValues().stream()
                .filter(ChainEntry::isPlaceholder)
                .collect(Collectors.toList());
        assertFalse(placeholders.isEmpty(), "Expected at least one placeholder entry");
        ChainEntry placeholder = placeholders.get(0);
        assertEquals("Wise Hipiotic Cow", placeholder.getText());
        assertNotNull(placeholder.getAuthor(), "Placeholder author should be set to the skipping player");
        assertEquals(player, placeholder.getAuthor());
        assertTrue(placeholder.isPlaceholder());
    }

    @Test
    void insertPlaceholders_NoDuplicateForPlayerWhoAlreadySubmitted() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain chain = buildChain(player, room);

        // Player already has an entry for round 1
        ChainEntry existingEntry = new ChainEntry();
        existingEntry.setRound(1);
        existingEntry.setPlaceholder(false);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainEntryRepository.findByChainAndRound(chain, 1)).thenReturn(Optional.of(existingEntry));
        when(chainRepository.findByRoomAndOriginPlayer(room, player)).thenReturn(Optional.of(chain));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));

        gameService.onRoundTimerExpired("ABCD1234", 1);

        // Should NOT save a placeholder for this player
        verify(chainEntryRepository, never()).save(argThat(e -> e.isPlaceholder()
                && e.getChain().getId().equals(chain.getId())));
    }

    @Test
    void insertPlaceholders_AuthorIsSkippingPlayer_OnPlaceholder() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain chain = buildChain(player, room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainEntryRepository.findByChainAndRound(chain, 1)).thenReturn(Optional.empty());
        when(chainRepository.findByRoomAndOriginPlayer(room, player)).thenReturn(Optional.of(chain));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));

        ArgumentCaptor<ChainEntry> captor = ArgumentCaptor.forClass(ChainEntry.class);
        when(chainEntryRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        gameService.onRoundTimerExpired("ABCD1234", 1);

        captor.getAllValues().stream()
                .filter(ChainEntry::isPlaceholder)
                .forEach(p -> {
                    assertNotNull(p.getAuthor(), "Placeholder author should be set to the skipping player");
                    assertEquals(player, p.getAuthor());
                });
    }

    // ---- endPromptingRound ----

    @Test
    void endPromptingRound_SetsPhaseToGenerating() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        Player player = buildPlayer(UUID.randomUUID(), room);
        Chain chain = buildChain(player, room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainEntryRepository.findByChainAndRound(chain, 1)).thenReturn(Optional.empty());
        when(chainRepository.findByRoomAndOriginPlayer(room, player)).thenReturn(Optional.of(chain));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));
        when(chainEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        gameService.onRoundTimerExpired("ABCD1234", 1);

        assertEquals(GamePhase.GENERATING, room.getPhase());
    }

    @Test
    void endPromptingRound_PublishesGeneratingPhaseChangedEvent() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        Player p1 = buildPlayer(UUID.randomUUID(), room);
        Player p2 = buildPlayer(UUID.randomUUID(), room);
        Chain c1 = buildChain(p1, room);
        Chain c2 = buildChain(p2, room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(p1, p2));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(c1, c2));
        when(chainRepository.findByRoomAndOriginPlayer(room, p1)).thenReturn(Optional.of(c1));
        when(chainRepository.findByRoomAndOriginPlayer(room, p2)).thenReturn(Optional.of(c2));
        when(chainEntryRepository.findByChainAndRound(any(), eq(1))).thenReturn(Optional.empty());
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        gameService.onRoundTimerExpired("ABCD1234", 1);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        boolean hasGeneratingEvent = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof PhaseChangedApplicationEvent p
                        && p.phase() == GamePhase.GENERATING);
        assertTrue(hasGeneratingEvent);
    }

    @Test
    void onStartImageGeneration_TriggersImageGenerationForEachChain() {
        Room room = buildRoom("ABCD1234", GamePhase.GENERATING);
        room.setCurrentRound(1);
        Player p1 = buildPlayer(UUID.randomUUID(), room);
        Player p2 = buildPlayer(UUID.randomUUID(), room);
        Chain c1 = buildChain(p1, room);
        Chain c2 = buildChain(p2, room);

        ChainEntry entry1 = new ChainEntry(); entry1.setText("prompt 1");
        ChainEntry entry2 = new ChainEntry(); entry2.setText("prompt 2");

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(c1, c2));
        when(chainEntryRepository.findByChainAndRound(c1, 1)).thenReturn(Optional.of(entry1));
        when(chainEntryRepository.findByChainAndRound(c2, 1)).thenReturn(Optional.of(entry2));
        when(chainEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));

        StartImageGenerationEvent event = new StartImageGenerationEvent("ABCD1234");
        gameService.onStartImageGeneration(event);

        verify(imageGenerationService, times(2)).generateImage(anyString());
    }

    // ---- endGuessingRound ----

    @Test
    void endGuessingRound_MidGame_SetsPhaseToGenerating() {
        Room room = buildRoom("ABCD1234", GamePhase.GUESSING);
        room.setCurrentRound(2);
        room.setTotalRounds(4);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain assignedChain = buildChain(buildPlayer(UUID.randomUUID(), room), room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(roundAssignmentService.getAssignedChain(room, player, 2)).thenReturn(assignedChain);
        when(chainEntryRepository.findByChainAndRound(assignedChain, 2)).thenReturn(Optional.empty());
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        when(chainRepository.findByRoom(room)).thenReturn(List.of(assignedChain));
        when(chainEntryRepository.findByChainAndRound(any(), eq(2))).thenReturn(Optional.empty());
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));

        gameService.onRoundTimerExpired("ABCD1234", 2);

        assertEquals(GamePhase.GENERATING, room.getPhase());
    }

    @Test
    void endGuessingRound_FinalRound_SetsPhaseToResults() {
        int n = 2;
        Room room = buildRoom("ABCD1234", GamePhase.GUESSING);
        room.setCurrentRound(n);
        room.setTotalRounds(n);
        Player player = buildPlayer(UUID.randomUUID(), room);
        Chain chain1 = buildChain(player, room);
        Player player2 = buildPlayer(UUID.randomUUID(), room);
        Chain chain2 = buildChain(player2, room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player, player2));
        when(roundAssignmentService.getAssignedChain(room, player, n)).thenReturn(chain2);
        when(roundAssignmentService.getAssignedChain(room, player2, n)).thenReturn(chain1);
        when(chainEntryRepository.findByChainAndRound(any(), eq(n))).thenReturn(Optional.empty());
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain1, chain2));
        when(chainEntryRepository.findByChainOrderByRoundAsc(any())).thenReturn(List.of());

        gameService.onRoundTimerExpired("ABCD1234", n);

        assertEquals(GamePhase.RESULTS, room.getPhase());
    }

    @Test
    void endGuessingRound_FinalRound_PublishesGameResultsApplicationEvent() {
        int n = 2;
        Room room = buildRoom("ABCD1234", GamePhase.GUESSING);
        room.setCurrentRound(n);
        room.setTotalRounds(n);
        Player player = buildPlayer(UUID.randomUUID(), room);
        Chain chain1 = buildChain(player, room);
        Player player2 = buildPlayer(UUID.randomUUID(), room);
        Chain chain2 = buildChain(player2, room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player, player2));
        when(roundAssignmentService.getAssignedChain(room, player, n)).thenReturn(chain2);
        when(roundAssignmentService.getAssignedChain(room, player2, n)).thenReturn(chain1);
        when(chainEntryRepository.findByChainAndRound(any(), eq(n))).thenReturn(Optional.empty());
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain1, chain2));
        when(chainEntryRepository.findByChainOrderByRoundAsc(any())).thenReturn(List.of());

        gameService.onRoundTimerExpired("ABCD1234", n);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        boolean hasResults = captor.getAllValues().stream()
                .anyMatch(e -> e instanceof GameResultsApplicationEvent);
        assertTrue(hasResults, "Expected GameResultsApplicationEvent to be published");
    }

    @Test
    void endGuessingRound_FinalRound_PlaceholderEntriesHaveNullPlayerIdAndIsPlaceholderTrue() {
        int n = 2;
        Room room = buildRoom("ABCD1234", GamePhase.GUESSING);
        room.setCurrentRound(n);
        room.setTotalRounds(n);
        Player player1 = buildPlayer(UUID.randomUUID(), room);
        Chain chain1 = buildChain(player1, room);
        Player player2 = buildPlayer(UUID.randomUUID(), room);
        Chain chain2 = buildChain(player2, room);

        // Create a placeholder entry
        ChainEntry placeholder = new ChainEntry();
        placeholder.setPlaceholder(true);
        placeholder.setAuthor(null);
        placeholder.setText("Wise Hipiotic Cow");
        placeholder.setRound(1);
        placeholder.setChain(chain1);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player1, player2));
        when(roundAssignmentService.getAssignedChain(room, player1, n)).thenReturn(chain2);
        when(roundAssignmentService.getAssignedChain(room, player2, n)).thenReturn(chain1);
        when(chainEntryRepository.findByChainAndRound(any(), eq(n))).thenReturn(Optional.empty());
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain1, chain2));
        when(chainEntryRepository.findByChainOrderByRoundAsc(chain1)).thenReturn(List.of(placeholder));
        when(chainEntryRepository.findByChainOrderByRoundAsc(chain2)).thenReturn(List.of());

        gameService.onRoundTimerExpired("ABCD1234", n);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        Optional<GameResultsApplicationEvent> resultsEvent = captor.getAllValues().stream()
                .filter(e -> e instanceof GameResultsApplicationEvent)
                .map(e -> (GameResultsApplicationEvent) e)
                .findFirst();
        assertTrue(resultsEvent.isPresent());
        boolean hasPlaceholder = resultsEvent.get().payload().chains().stream()
                .flatMap(c -> c.entries().stream())
                .anyMatch(e -> e.isPlaceholder() && e.playerId() == null);
        assertTrue(hasPlaceholder);
    }

    // ---- onAllImagesReady ----

    @Test
    void onAllImagesReady_IncrementsRoundAndSetsPhaseToGuessing() {
        Room room = buildRoom("ABCD1234", GamePhase.GENERATING);
        room.setCurrentRound(1);
        room.setTotalRounds(4);

        Player player = buildPlayer(UUID.randomUUID(), room);
        Chain assignedChain = buildChain(buildPlayer(UUID.randomUUID(), room), room);

        RoundAssignment assignment = new RoundAssignment();
        assignment.setChain(assignedChain);
        assignment.setPlayer(player);
        assignment.setRound(2);

        ChainEntry imageEntry = new ChainEntry();
        imageEntry.setImageUrl("/api/images/game/img1");
        imageEntry.setRound(1);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(roundAssignmentService.getAssignedChain(room, player, 2)).thenReturn(assignedChain);
        when(chainEntryRepository.findByChainAndRound(assignedChain, 1)).thenReturn(Optional.of(imageEntry));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        gameService.onAllImagesReady("ABCD1234");

        assertEquals(GamePhase.GUESSING, room.getPhase());
        assertEquals(2, room.getCurrentRound());
    }

    @Test
    void onAllImagesReady_StartsGuessingTimer() {
        Room room = buildRoom("ABCD1234", GamePhase.GENERATING);
        room.setCurrentRound(1);
        room.setTotalRounds(4);

        Player player = buildPlayer(UUID.randomUUID(), room);
        Chain assignedChain = buildChain(buildPlayer(UUID.randomUUID(), room), room);

        ChainEntry imageEntry = new ChainEntry();
        imageEntry.setImageUrl("/api/images/game/img1");

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(roundAssignmentService.getAssignedChain(room, player, 2)).thenReturn(assignedChain);
        when(chainEntryRepository.findByChainAndRound(assignedChain, 1)).thenReturn(Optional.of(imageEntry));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        gameService.onAllImagesReady("ABCD1234");

        verify(timerService).startRoundTimer("ABCD1234", 2, GUESSING_SECONDS);
    }

    @Test
    void onAllImagesReady_PublishesRoundReadyEvent() {
        Room room = buildRoom("ABCD1234", GamePhase.GENERATING);
        room.setCurrentRound(1);
        room.setTotalRounds(4);

        Player player = buildPlayer(UUID.randomUUID(), room);
        Chain assignedChain = buildChain(buildPlayer(UUID.randomUUID(), room), room);

        ChainEntry imageEntry = new ChainEntry();
        imageEntry.setImageUrl("/api/images/game/img1");

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(roundAssignmentService.getAssignedChain(room, player, 2)).thenReturn(assignedChain);
        when(chainEntryRepository.findByChainAndRound(assignedChain, 1)).thenReturn(Optional.of(imageEntry));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        gameService.onAllImagesReady("ABCD1234");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(e -> e instanceof RoundReadyApplicationEvent));
    }

    @Test
    void onAllImagesReady_SetsRoundStartedAt_ToCurrentInstant() {
        Room room = buildRoom("ABCD1234", GamePhase.GENERATING);
        room.setCurrentRound(1);
        room.setTotalRounds(4);

        Player player = buildPlayer(UUID.randomUUID(), room);
        Chain assignedChain = buildChain(buildPlayer(UUID.randomUUID(), room), room);

        ChainEntry imageEntry = new ChainEntry();
        imageEntry.setImageUrl("/api/images/game/img1");

        Instant before = Instant.now();

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(roundAssignmentService.getAssignedChain(room, player, 2)).thenReturn(assignedChain);
        when(chainEntryRepository.findByChainAndRound(assignedChain, 1)).thenReturn(Optional.of(imageEntry));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        gameService.onAllImagesReady("ABCD1234");

        Instant after = Instant.now();
        assertNotNull(room.getRoundStartedAt());
        assertFalse(room.getRoundStartedAt().isBefore(before.minusSeconds(2)));
        assertFalse(room.getRoundStartedAt().isAfter(after.plusSeconds(2)));
    }

    // ---- advanceShowcase ----

    @Test
    void advanceShowcase_PublishesShowcaseEvent_WithIncrementedIndex() {
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.RESULTS);
        room.setHostId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));

        gameService.advanceShowcase("ABCD1234", hostId);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        Optional<ShowcaseApplicationEvent> showcaseEvent = captor.getAllValues().stream()
                .filter(e -> e instanceof ShowcaseApplicationEvent)
                .map(e -> (ShowcaseApplicationEvent) e)
                .findFirst();
        assertTrue(showcaseEvent.isPresent());
        assertEquals(1, showcaseEvent.get().chainIndex());
    }

    @Test
    void advanceShowcase_NonHost_DoesNotPublishShowcaseEvent() {
        UUID hostId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.RESULTS);
        room.setHostId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));

        gameService.advanceShowcase("ABCD1234", otherId);

        verify(eventPublisher, never()).publishEvent(any(ShowcaseApplicationEvent.class));
    }

    // ---- N-3: Non-host call must not increment showcase counter ----

    @Test
    void advanceShowcase_NonHost_DoesNotIncrementCounter_SubsequentHostCallProducesIndexOne() {
        UUID hostId = UUID.randomUUID();
        UUID nonHostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.RESULTS);
        room.setHostId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));

        // Non-host call — must be ignored, counter stays at 0
        gameService.advanceShowcase("ABCD1234", nonHostId);

        verify(eventPublisher, never()).publishEvent(any(ShowcaseApplicationEvent.class));

        // Host call — counter was not pre-incremented, so the first host advance produces index 1
        gameService.advanceShowcase("ABCD1234", hostId);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        Optional<ShowcaseApplicationEvent> showcaseEvent = captor.getAllValues().stream()
                .filter(e -> e instanceof ShowcaseApplicationEvent)
                .map(e -> (ShowcaseApplicationEvent) e)
                .findFirst();
        assertTrue(showcaseEvent.isPresent(), "ShowcaseApplicationEvent must be published by host call");
        assertEquals(1, showcaseEvent.get().chainIndex(),
                "chainIndex must be 1 for the first host advance; non-host must not have pre-incremented it");
    }

    // ---- Post-review fix 1 — Idempotency guard: submitPrompt ignores second submission ----

    @Test
    void submitPrompt_IgnoresSecondSubmission_WhenPlayerAlreadySubmittedThisRound() {
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        room.setTotalRounds(2);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain chain = buildChain(player, room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findById(playerId)).thenReturn(Optional.of(player));
        when(chainRepository.findByRoomAndOriginPlayer(room, player)).thenReturn(Optional.of(chain));
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        // First call: 0 submitted (before save)
        when(chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(any(), anyInt())).thenReturn(0L);
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));

        // Idempotency: after the first save, the entry exists — second call should exit early.
        // We simulate this by having existsByChain... return false on first call, true on second.
        when(chainEntryRepository.existsByChainOriginPlayerAndRoundAndIsPlaceholderFalse(player, 1))
                .thenReturn(false)   // first call passes the guard
                .thenReturn(true);   // second call is rejected

        // First submission
        gameService.submitPrompt("ABCD1234", playerId, "First prompt");

        // Second submission (same player, same round) — must be ignored
        gameService.submitPrompt("ABCD1234", playerId, "Second prompt");

        // ChainEntry.save must be called exactly once
        verify(chainEntryRepository, times(1)).save(any(ChainEntry.class));

        // SubmissionUpdateApplicationEvent must be published exactly once
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        long updateCount = captor.getAllValues().stream()
                .filter(e -> e instanceof SubmissionUpdateApplicationEvent)
                .count();
        assertEquals(1L, updateCount,
                "SubmissionUpdateApplicationEvent must be published exactly once for the first submission");
    }

    // ---- Post-review fix 1 — Idempotency guard: submitGuess ignores second submission ----

    @Test
    void submitGuess_IgnoresSecondSubmission_WhenPlayerAlreadySubmittedThisRound() {
        Room room = buildRoom("ABCD1234", GamePhase.GUESSING);
        room.setCurrentRound(2);
        room.setTotalRounds(2);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain assignedChain = buildChain(buildPlayer(UUID.randomUUID(), room), room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findById(playerId)).thenReturn(Optional.of(player));
        when(roundAssignmentService.getAssignedChain(room, player, 2)).thenReturn(assignedChain);
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(assignedChain));
        when(chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(any(), anyInt())).thenReturn(0L);

        // Idempotency guard: second call is rejected
        when(chainEntryRepository.existsByChainAndRoundAndAuthorAndIsPlaceholderFalse(assignedChain, 2, player))
                .thenReturn(false)   // first call passes
                .thenReturn(true);   // second call is rejected

        // First submission
        gameService.submitGuess("ABCD1234", playerId, "First guess");

        // Second submission (same player, same round) — must be ignored
        gameService.submitGuess("ABCD1234", playerId, "Second guess");

        // ChainEntry.save must be called exactly once
        verify(chainEntryRepository, times(1)).save(any(ChainEntry.class));

        // SubmissionUpdateApplicationEvent must be published exactly once
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        long updateCount = captor.getAllValues().stream()
                .filter(e -> e instanceof SubmissionUpdateApplicationEvent)
                .count();
        assertEquals(1L, updateCount,
                "SubmissionUpdateApplicationEvent must be published exactly once for the first guess");
    }

    // ---- Fix 3: submission threshold uses totalRounds, not connected count ----

    @Test
    void submitPrompt_DoesNotEndRound_WhenDisconnectedPlayerReducesConnectedCount() {
        // 2-player game, but one disconnects mid-round — only 1 connected.
        // totalRounds is 2 (set at game start), so 1 submission should NOT end the round.
        Room room = buildRoom("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        room.setTotalRounds(2);
        UUID playerId = UUID.randomUUID();
        Player player = buildPlayer(playerId, room);
        Chain chain = buildChain(player, room);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findById(playerId)).thenReturn(Optional.of(player));
        when(chainRepository.findByRoomAndOriginPlayer(room, player)).thenReturn(Optional.of(chain));
        when(chainEntryRepository.save(any(ChainEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        // Only 1 player connected (the other disconnected)
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        // 1 out of 2 submitted
        when(chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(any(), anyInt())).thenReturn(1L);
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));

        gameService.submitPrompt("ABCD1234", playerId, "A prompt");

        // Should NOT cancel timer or end the round — total is 2 (from totalRounds), not 1
        verify(timerService, never()).cancelTimer(anyString());
        assertEquals(GamePhase.PROMPTING, room.getPhase());
    }

    // ---- startGame — chain style assignment ----

    @Test
    void startGame_AssignsUniqueStyleToEachChain() {
        int n = 3;
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        List<Player> players = buildPlayers(n, room);
        players.get(0).setId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(players);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<Chain> chainCaptor = ArgumentCaptor.forClass(Chain.class);
        when(chainRepository.save(chainCaptor.capture())).thenAnswer(inv -> {
            Chain c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        gameService.startGame("ABCD1234", hostId);

        List<Chain> saved = chainCaptor.getAllValues();
        assertEquals(n, saved.size());
        List<String> styles = saved.stream().map(Chain::getStyle).toList();
        assertTrue(styles.stream().allMatch(Objects::nonNull), "Every chain must have a non-null style");
        assertEquals(n, new HashSet<>(styles).size(), "All chain styles must be unique");
    }

    @Test
    void startGame_ThrowsGameException_WhenNotEnoughStyles() {
        int n = 3;
        UUID hostId = UUID.randomUUID();
        Room room = buildRoom("ABCD1234", GamePhase.LOBBY);
        room.setHostId(hostId);

        List<Player> players = buildPlayers(n, room);
        players.get(0).setId(hostId);

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(players);
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(artStyleRepository.findAll()).thenReturn(buildArtStyles(2)); // only 2 for 3 players

        assertThrows(GameException.class, () -> gameService.startGame("ABCD1234", hostId));
    }

    // ---- triggerImageGeneration — prompt decoration ----

    @Test
    void onStartImageGeneration_DecoratesPromptWithChainStyle() {
        Room room = buildRoom("ABCD1234", GamePhase.GENERATING);
        room.setCurrentRound(1);
        Player p = buildPlayer(UUID.randomUUID(), room);
        Chain chain = buildChain(p, room);
        chain.setStyle("pixel art");

        ChainEntry entry = new ChainEntry();
        entry.setText("a dragon");

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(chainEntryRepository.findByChainAndRound(chain, 1)).thenReturn(Optional.of(entry));
        when(chainEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));

        gameService.onStartImageGeneration(new StartImageGenerationEvent("ABCD1234"));

        verify(imageGenerationService).generateImage("a dragon, pixel art style");
    }

    @Test
    void onStartImageGeneration_UsesPlainPrompt_WhenChainStyleIsNull() {
        Room room = buildRoom("ABCD1234", GamePhase.GENERATING);
        room.setCurrentRound(1);
        Player p = buildPlayer(UUID.randomUUID(), room);
        Chain chain = buildChain(p, room); // style is null by default

        ChainEntry entry = new ChainEntry();
        entry.setText("a dragon");

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(chainEntryRepository.findByChainAndRound(chain, 1)).thenReturn(Optional.of(entry));
        when(chainEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));

        gameService.onStartImageGeneration(new StartImageGenerationEvent("ABCD1234"));

        verify(imageGenerationService).generateImage("a dragon");
    }

    // ---- triggerImageGeneration — img2img ----

    @Test
    void triggerImageGeneration_UsesTxt2img_ForRound1() {
        Room room = buildRoom("ABCD1234", GamePhase.GENERATING);
        room.setCurrentRound(1);
        Player p = buildPlayer(UUID.randomUUID(), room);
        Chain chain = buildChain(p, room);

        ChainEntry entry = new ChainEntry();
        entry.setText("a sunset");

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(chainEntryRepository.findByChainAndRound(chain, 1)).thenReturn(Optional.of(entry));
        when(chainEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/url"));

        gameService.onStartImageGeneration(new StartImageGenerationEvent("ABCD1234"));

        verify(imageGenerationService).generateImage(anyString());
        verify(imageGenerationService, never()).generateImageFromImage(anyString(), any(byte[].class));
    }

    @Test
    void triggerImageGeneration_UsesImg2img_ForRound2_WhenPreviousImageExists() {
        Room room = buildRoom("ABCD1234", GamePhase.GENERATING);
        room.setCurrentRound(2);
        Player p = buildPlayer(UUID.randomUUID(), room);
        Chain chain = buildChain(p, room);

        // Current round entry (round 2)
        ChainEntry currentEntry = new ChainEntry();
        currentEntry.setText("a cat on a mountain");

        // Previous round entry (round 1) with an image
        ChainEntry prevEntry = new ChainEntry();
        prevEntry.setImageUrl("/api/images/game-abc/prev-img");

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(chainEntryRepository.findByChainAndRound(chain, 2)).thenReturn(Optional.of(currentEntry));
        when(chainEntryRepository.findByChainAndRound(chain, 1)).thenReturn(Optional.of(prevEntry));
        when(chainEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(imageStorageService.fetchImageBytes("/api/images/game-abc/prev-img"))
                .thenReturn(new byte[]{1, 2, 3});
        when(imageGenerationService.generateImageFromImage(anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture("/img/img2img-url"));

        gameService.onStartImageGeneration(new StartImageGenerationEvent("ABCD1234"));

        verify(imageGenerationService).generateImageFromImage(anyString(), any(byte[].class));
        verify(imageStorageService).fetchImageBytes("/api/images/game-abc/prev-img");
    }

    @Test
    void triggerImageGeneration_FallsBackToTxt2img_WhenPreviousImageFetchFails() {
        Room room = buildRoom("ABCD1234", GamePhase.GENERATING);
        room.setCurrentRound(2);
        Player p = buildPlayer(UUID.randomUUID(), room);
        Chain chain = buildChain(p, room);

        // Current round entry (round 2)
        ChainEntry currentEntry = new ChainEntry();
        currentEntry.setText("a dog in space");

        // Previous round entry (round 1) with an image URL
        ChainEntry prevEntry = new ChainEntry();
        prevEntry.setImageUrl("/api/images/game-abc/prev-img");

        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(chainEntryRepository.findByChainAndRound(chain, 2)).thenReturn(Optional.of(currentEntry));
        when(chainEntryRepository.findByChainAndRound(chain, 1)).thenReturn(Optional.of(prevEntry));
        when(chainEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(imageStorageService.fetchImageBytes("/api/images/game-abc/prev-img"))
                .thenThrow(new RuntimeException("File not found"));
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("/img/fallback-url"));

        gameService.onStartImageGeneration(new StartImageGenerationEvent("ABCD1234"));

        // Should fall back to txt2img
        verify(imageGenerationService).generateImage(anyString());
        verify(imageGenerationService, never()).generateImageFromImage(anyString(), any(byte[].class));
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

    private List<Player> buildPlayers(int n, Room room) {
        return IntStream.range(0, n).mapToObj(i -> {
            Player p = new Player();
            p.setId(UUID.randomUUID());
            p.setToken(UUID.randomUUID());
            p.setAlias("Player" + i);
            p.setAvatarId("icon-" + i);
            p.setRoom(room);
            p.setConnected(true);
            p.setJoinedAt(Instant.now().plusSeconds(i));
            return p;
        }).collect(Collectors.toList());
    }

    private Chain buildChain(Player player, Room room) {
        Chain chain = new Chain();
        chain.setId(UUID.randomUUID());
        chain.setRoom(room);
        chain.setOriginPlayer(player);
        return chain;
    }

    private ArtStyle buildArtStyle(String name) {
        ArtStyle s = new ArtStyle();
        s.setName(name);
        return s;
    }

    private List<ArtStyle> buildArtStyles(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> buildArtStyle("style-" + i))
                .collect(Collectors.toList());
    }
}
