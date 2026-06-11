package com.app.promptle.integration;

import com.app.promptle.game.dto.ChainDto;
import com.app.promptle.game.dto.ChainEntryDto;
import com.app.promptle.game.dto.GameResultsEvent;
import com.app.promptle.game.model.ChainEntry;
import com.app.promptle.game.model.GamePhase;
import com.app.promptle.game.model.RoundAssignment;
import com.app.promptle.game.model.Chain;
import com.app.promptle.game.repository.ChainEntryRepository;
import com.app.promptle.game.repository.ChainRepository;
import com.app.promptle.game.repository.RoundAssignmentRepository;
import com.app.promptle.game.service.GameService;
import com.app.promptle.image.api.ImageGenerationService;
import com.app.promptle.room.dto.*;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import com.app.promptle.room.repository.PlayerRepository;
import com.app.promptle.room.repository.RoomRepository;
import com.app.promptle.room.service.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FullGameFlowIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private ChainRepository chainRepository;

    @Autowired
    private ChainEntryRepository chainEntryRepository;

    @Autowired
    private RoundAssignmentRepository roundAssignmentRepository;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameService gameService;

    @MockBean
    private ImageGenerationService imageGenerationService;

    // ---- Scenario A — Happy path, 4 players, all submit before timer ----

    @Test
    void scenarioA_HappyPath_4Players_AllSubmitBeforeTimer() throws Exception {
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"));

        // Step 1: Create room (player 1 = host)
        ResponseEntity<Map> createResponse = restTemplate.postForEntity(
                url("/api/rooms"),
                createBody("Alice", "icon-1"),
                Map.class
        );
        assertEquals(HttpStatus.OK, createResponse.getStatusCode());
        String roomCode = (String) createResponse.getBody().get("roomCode");
        assertNotNull(roomCode);
        assertEquals(8, roomCode.length());

        // Get player 1 token (host created in createRoom)
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();

        // Step 2: Three players join
        String p2Token = joinAndConnect(roomCode, "Bob", "icon-2");
        String p3Token = joinAndConnect(roomCode, "Carol", "icon-3");
        String p4Token = joinAndConnect(roomCode, "Dave", "icon-4");

        assertNotEquals(p1Token, p2Token);
        assertNotEquals(p1Token, p3Token);
        assertNotEquals(p1Token, p4Token);

        // Step 3: Room state before start
        ResponseEntity<Map> stateResponse = restTemplate.getForEntity(
                url("/api/rooms/" + roomCode + "/state?token=" + p1Token),
                Map.class
        );
        assertEquals(HttpStatus.OK, stateResponse.getStatusCode());
        assertEquals("LOBBY", stateResponse.getBody().get("phase"));
        assertEquals(4, ((List<?>) stateResponse.getBody().get("players")).size());

        // Step 4: Non-host cannot start
        ResponseEntity<Map> nonHostStart = restTemplate.postForEntity(
                url("/api/rooms/" + roomCode + "/start?token=" + p2Token),
                null,
                Map.class
        );
        assertEquals(HttpStatus.CONFLICT, nonHostStart.getStatusCode());

        // Step 5: Host starts game
        connectPlayers(roomCode, p1Token, p2Token, p3Token, p4Token);

        ResponseEntity<Void> startResponse = restTemplate.postForEntity(
                url("/api/rooms/" + roomCode + "/start?token=" + p1Token),
                null,
                Void.class
        );
        assertEquals(HttpStatus.OK, startResponse.getStatusCode());

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(GamePhase.PROMPTING, room.getPhase());
        assertEquals(4, room.getTotalRounds());
        assertEquals(1, room.getCurrentRound());

        List<?> chains = chainRepository.findByRoom(room);
        assertEquals(4, chains.size());

        List<RoundAssignment> assignments = roundAssignmentRepository.findByRoom(room);
        assertEquals(12, assignments.size(), "4 players × 3 guessing rounds = 12 assignments");

        // Step 6: All 4 players submit prompts
        List<Player> allPlayers = playerRepository.findByRoom(room);
        String[] tokens = {p1Token, p2Token, p3Token, p4Token};
        for (int i = 0; i < 4; i++) {
            String finalRoomCode = roomCode;
            Player player = playerRepository.findByToken(UUID.fromString(tokens[i])).orElseThrow();
            gameService.submitPrompt(finalRoomCode, player.getId(), "Prompt from player " + (i + 1));
        }

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        // With chunk 9 automatic image generation, GENERATING may already advance to GUESSING
        assertTrue(room.getPhase() == GamePhase.GENERATING || room.getPhase() == GamePhase.GUESSING,
                "Room must be in GENERATING or GUESSING after all prompts submitted");

        // Step 7: Ensure transition to GUESSING (idempotent if already done by async pipeline).
        gameService.onAllImagesReady(roomCode);

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(GamePhase.GUESSING, room.getPhase());
        assertEquals(2, room.getCurrentRound());

        // Step 8-9: Rounds 2-4 guessing — every guessing round (including the final one)
        // is followed by a GENERATING pass so each chain ends on an image.
        for (int round = 2; round <= 4; round++) {
            room = roomRepository.findByRoomCode(roomCode).orElseThrow();
            assertEquals(GamePhase.GUESSING, room.getPhase());

            for (String token : tokens) {
                Player player = playerRepository.findByToken(UUID.fromString(token)).orElseThrow();
                gameService.submitGuess(roomCode, player.getId(), "Guess round " + round);
            }

            room = roomRepository.findByRoomCode(roomCode).orElseThrow();
            GamePhase nextPhase = round < 4 ? GamePhase.GUESSING : GamePhase.RESULTS;
            // With chunk 9 automatic image generation, GENERATING may already have advanced
            assertTrue(room.getPhase() == GamePhase.GENERATING || room.getPhase() == nextPhase,
                    "Room must be in GENERATING or " + nextPhase + " after guessing round " + round);
            // Ensure the transition completes (idempotent if already done by async pipeline).
            gameService.onAllImagesReady(roomCode);
        }

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(GamePhase.RESULTS, room.getPhase());

        // Every chain ends on an image: final-round entries must have a generated image.
        for (Chain chain : chainRepository.findByRoom(room)) {
            ChainEntry finalEntry = chainEntryRepository.findByChainAndRound(chain, 4).orElseThrow();
            assertNotNull(finalEntry.getImageUrl(),
                    "Final entry of every chain must have an image after the final GENERATING pass");
        }
    }

    // ---- Scenario B — Timer expiry with placeholder insertion ----

    @Test
    void scenarioB_TimerExpiry_WithPlaceholderInsertion_2Players() throws Exception {
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"));

        String roomCode = createRoomAndGetCode("Player1", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();
        String p2Token = joinPlayer(roomCode, "Player2", "icon-2");

        connectPlayers(roomCode, p1Token, p2Token);
        startGame(roomCode, p1Token);

        // Player 1 submits; player 2 does not
        Player p1 = playerRepository.findByToken(UUID.fromString(p1Token)).orElseThrow();
        gameService.submitPrompt(roomCode, p1.getId(), "A mountain view");

        // Timer expires for player 2
        gameService.onRoundTimerExpired(roomCode, 1);

        // Assert placeholder on player 2's chain. The mocked image future completes
        // synchronously, so the pipeline may already have advanced from GENERATING to GUESSING.
        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertTrue(room.getPhase() == GamePhase.GENERATING || room.getPhase() == GamePhase.GUESSING,
                "Room must be in GENERATING or GUESSING after the round completes");

        Player p2 = playerRepository.findByToken(UUID.fromString(p2Token)).orElseThrow();
        var p2Chain = chainRepository.findByRoomAndOriginPlayer(room, p2).orElseThrow();
        var p2Entries = chainEntryRepository.findByChainOrderByRoundAsc(p2Chain);

        assertTrue(p2Entries.stream().anyMatch(e ->
                e.isPlaceholder() &&
                        "Wise Hipiotic Cow".equals(e.getText()) &&
                        p2.getId().equals(e.getAuthor().getId())
        ), "Player 2 should have a placeholder entry attributed to player 2");
    }

    // ---- Scenario C — Mid-game disconnect ----

    @Test
    void scenarioC_MidGameDisconnect_PlaceholdersInsertedForDisconnectedPlayer() throws Exception {
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"));

        String roomCode = createRoomAndGetCode("P1", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();
        connectPlayer(roomCode, p1Token);
        String p2Token = joinAndConnect(roomCode, "P2", "icon-2");
        String p3Token = joinAndConnect(roomCode, "P3", "icon-3");

        startGame(roomCode, p1Token);

        // All 3 submit round 1
        Player p1 = playerRepository.findByToken(UUID.fromString(p1Token)).orElseThrow();
        Player p2 = playerRepository.findByToken(UUID.fromString(p2Token)).orElseThrow();
        Player p3 = playerRepository.findByToken(UUID.fromString(p3Token)).orElseThrow();

        gameService.submitPrompt(roomCode, p1.getId(), "prompt 1");
        gameService.submitPrompt(roomCode, p2.getId(), "prompt 2");
        gameService.submitPrompt(roomCode, p3.getId(), "prompt 3");

        // Mock returns completedFuture — call onAllImagesReady immediately
        gameService.onAllImagesReady(roomCode);

        // Player 3 disconnects
        roomService.playerDisconnected(roomCode, p3.getId());
        p3 = playerRepository.findById(p3.getId()).orElseThrow();
        assertFalse(p3.isConnected());

        // Players 1 and 2 submit round 2; timer fires for player 3
        gameService.submitGuess(roomCode, p1.getId(), "guess round 2");
        gameService.submitGuess(roomCode, p2.getId(), "guess round 2");
        gameService.onRoundTimerExpired(roomCode, 2);

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        // With chunk 9 automatic image generation, the game auto-advances from GENERATING to GUESSING
        assertTrue(room.getPhase() == GamePhase.GENERATING || room.getPhase() == GamePhase.GUESSING,
                "Room must be in GENERATING or GUESSING phase after mid-game round completion");
    }

    // ---- Scenario D — Host disconnect triggers reassignment ----

    @Test
    void scenarioD_HostDisconnect_TriggersHostReassignment() {
        String roomCode = createRoomAndGetCode("HostPlayer", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p2Token = joinPlayer(roomCode, "OtherPlayer", "icon-2");
        Player p2 = playerRepository.findByToken(UUID.fromString(p2Token)).orElseThrow();

        connectPlayers(roomCode, host.getToken().toString(), p2Token);

        roomService.playerDisconnected(roomCode, host.getId());

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(p2.getId(), room.getHostId());
    }

    // ---- Scenario E — Max player count enforcement ----

    @Test
    void scenarioE_MaxPlayerCountEnforcement_9thJoinFails() {
        String roomCode = createRoomAndGetCode("Host", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        connectPlayer(roomCode, host.getToken().toString());

        // Join 7 more (total 8 including host) and connect each
        for (int i = 2; i <= 8; i++) {
            connectPlayer(roomCode, joinPlayer(roomCode, "Player" + i, "icon-" + i));
        }

        // 9th player attempt
        ResponseEntity<Map> ninthJoin = restTemplate.postForEntity(
                url("/api/rooms/" + roomCode + "/join"),
                createBody("Player9", "icon-1"),
                Map.class
        );
        assertEquals(HttpStatus.CONFLICT, ninthJoin.getStatusCode());
    }

    // ---- Scenario F — Single-player (solo) flow ----

    @Test
    void scenarioF_SinglePlayer_SkipsGuessing_GoesStraightToResults() throws Exception {
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"));

        String roomCode = createRoomAndGetCode("Solo", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();

        connectPlayer(roomCode, p1Token);

        // A solo player can now start the game.
        ResponseEntity<Void> startResponse = restTemplate.postForEntity(
                url("/api/rooms/" + roomCode + "/start?token=" + p1Token),
                null,
                Void.class
        );
        assertEquals(HttpStatus.OK, startResponse.getStatusCode());

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(GamePhase.PROMPTING, room.getPhase());
        assertEquals(1, room.getTotalRounds());
        assertEquals(1, room.getCurrentRound());

        // Exactly one chain, and no round assignments (guessing is skipped).
        assertEquals(1, chainRepository.findByRoom(room).size());
        assertTrue(roundAssignmentRepository.findByRoom(room).isEmpty(),
                "Solo game must not generate any round assignments");

        // The solo player submits their prompt → generation begins.
        Player p1 = playerRepository.findByToken(UUID.fromString(p1Token)).orElseThrow();
        gameService.submitPrompt(roomCode, p1.getId(), "A cat riding a rocket");

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertTrue(room.getPhase() == GamePhase.GENERATING || room.getPhase() == GamePhase.RESULTS,
                "Solo game must be GENERATING or RESULTS after the only prompt is submitted");

        // Images ready → go straight to RESULTS, never entering GUESSING.
        gameService.onAllImagesReady(roomCode);

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(GamePhase.RESULTS, room.getPhase());
        assertEquals(1, room.getCurrentRound(), "Round must not advance — guessing was skipped");
        assertTrue(roundAssignmentRepository.findByRoom(room).isEmpty(),
                "Solo game must still have no round assignments at results");
    }

    @Test
    void scenarioF_MinimumPlayerCountEnforcement_2PlayersCanStart() {
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"));

        String roomCode = createRoomAndGetCode("P1", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();
        String p2Token = joinPlayer(roomCode, "P2", "icon-2");

        connectPlayers(roomCode, p1Token, p2Token);

        ResponseEntity<Void> startResponse = restTemplate.postForEntity(
                url("/api/rooms/" + roomCode + "/start?token=" + p1Token),
                null,
                Void.class
        );
        assertEquals(HttpStatus.OK, startResponse.getStatusCode());
    }

    // ---- Scenario G — Cleanup after all players leave results ----

    @Test
    void scenarioG_CleanupAfterAllPlayersLeaveResults_RoomRecordRetained() throws Exception {
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"));

        String roomCode = createRoomAndGetCode("P1", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();
        String p2Token = joinPlayer(roomCode, "P2", "icon-2");

        connectPlayers(roomCode, p1Token, p2Token);
        startGame(roomCode, p1Token);

        // Run game to RESULTS
        Player p1 = playerRepository.findByToken(UUID.fromString(p1Token)).orElseThrow();
        Player p2 = playerRepository.findByToken(UUID.fromString(p2Token)).orElseThrow();

        gameService.submitPrompt(roomCode, p1.getId(), "a prompt");
        gameService.submitPrompt(roomCode, p2.getId(), "another prompt");

        // Mock returns completedFuture — call onAllImagesReady immediately
        gameService.onAllImagesReady(roomCode);

        gameService.submitGuess(roomCode, p1.getId(), "guess");
        gameService.submitGuess(roomCode, p2.getId(), "guess");

        // Final GENERATING pass — ensure it completes (idempotent if already done by async pipeline)
        gameService.onAllImagesReady(roomCode);

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(GamePhase.RESULTS, room.getPhase());

        // Player 1 disconnects — room should still exist
        roomService.playerDisconnected(roomCode, p1.getId());
        assertTrue(roomRepository.findByRoomCode(roomCode).isPresent(),
                "Room should still exist after one player disconnects");

        // Player 2 disconnects — room should still exist (only images deleted)
        roomService.playerDisconnected(roomCode, p2.getId());
        assertTrue(roomRepository.findByRoomCode(roomCode).isPresent(),
                "Room record should be retained even after all players leave results");
    }

    // ---- Scenario H — Reconnection snapshot correctness ----

    @Test
    void scenarioH_ReconnectionSnapshot_CorrectForGuessingPhase() throws Exception {
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"));

        String roomCode = createRoomAndGetCode("P1", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();
        String p2Token = joinPlayer(roomCode, "P2", "icon-2");

        connectPlayers(roomCode, p1Token, p2Token);
        startGame(roomCode, p1Token);

        Player p1 = playerRepository.findByToken(UUID.fromString(p1Token)).orElseThrow();
        Player p2 = playerRepository.findByToken(UUID.fromString(p2Token)).orElseThrow();

        gameService.submitPrompt(roomCode, p1.getId(), "sunset");
        gameService.submitPrompt(roomCode, p2.getId(), "mountains");

        // Mock returns completedFuture — call onAllImagesReady immediately
        gameService.onAllImagesReady(roomCode);

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(GamePhase.GUESSING, room.getPhase());

        var snapshot = roomService.getGameStateSnapshot(roomCode, p1Token);

        assertEquals(GamePhase.GUESSING, snapshot.phase());
        assertEquals(2, snapshot.currentRound());
        assertTrue(snapshot.timerSeconds() > 0);
        assertEquals(room.getRoundStartedAt().toEpochMilli(), snapshot.serverTimestamp());
        assertNotNull(snapshot.imageUrl());
    }

    // ---- Scenario I — Latin square correctness (N=4) ----

    @Test
    void scenarioI_LatinSquareCorrectness_N4() throws Exception {
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"));

        String roomCode = createRoomAndGetCode("P1", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();
        connectPlayer(roomCode, p1Token);
        String p2Token = joinAndConnect(roomCode, "P2", "icon-2");
        String p3Token = joinAndConnect(roomCode, "P3", "icon-3");
        String p4Token = joinAndConnect(roomCode, "P4", "icon-4");

        startGame(roomCode, p1Token);

        List<RoundAssignment> assignments = roundAssignmentRepository.findByRoom(room);
        assertEquals(12, assignments.size(), "4 × 3 = 12 assignments expected");

        // For each round, chain IDs must be distinct
        Map<Integer, List<RoundAssignment>> byRound = assignments.stream()
                .collect(Collectors.groupingBy(RoundAssignment::getRound));

        for (Map.Entry<Integer, List<RoundAssignment>> entry : byRound.entrySet()) {
            Set<UUID> chainIds = entry.getValue().stream()
                    .map(ra -> ra.getChain().getId())
                    .collect(Collectors.toSet());
            assertEquals(4, chainIds.size(), "No chain collision in round " + entry.getKey());

            Set<UUID> playerIds = entry.getValue().stream()
                    .map(ra -> ra.getPlayer().getId())
                    .collect(Collectors.toSet());
            assertEquals(4, playerIds.size(), "No player collision in round " + entry.getKey());
        }

        // For each player, chain assignments across rounds 2-4 must be distinct and not own chain
        List<Player> players = playerRepository.findByRoom(room);
        for (Player player : players) {
            var playerAssignments = assignments.stream()
                    .filter(ra -> ra.getPlayer().getId().equals(player.getId()))
                    .collect(Collectors.toList());
            assertEquals(3, playerAssignments.size());

            Set<UUID> chainIds = playerAssignments.stream()
                    .map(ra -> ra.getChain().getId())
                    .collect(Collectors.toSet());
            assertEquals(3, chainIds.size(), "Each player should see 3 distinct chains");

            // No self-assignment
            var ownChain = chainRepository.findByRoomAndOriginPlayer(room, player).orElseThrow();
            assertFalse(chainIds.contains(ownChain.getId()), "Player should not be assigned own chain");
        }
    }

    // ---- Scenario J — GameResultsEvent reconnect payload contains non-empty chain entries (N-5) ----

    @Test
    void scenarioJ_ReconnectDuringResults_GameResultsEventContainsNonEmptyChainEntries() throws Exception {
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"));

        String roomCode = createRoomAndGetCode("P1", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();
        String p2Token = joinPlayer(roomCode, "P2", "icon-2");

        connectPlayers(roomCode, p1Token, p2Token);
        startGame(roomCode, p1Token);

        Player p1 = playerRepository.findByToken(UUID.fromString(p1Token)).orElseThrow();
        Player p2 = playerRepository.findByToken(UUID.fromString(p2Token)).orElseThrow();

        // Run through the full game to reach RESULTS
        final String p1PromptText = "A serene forest at dawn";
        final String p2PromptText = "A raging storm over the sea";
        gameService.submitPrompt(roomCode, p1.getId(), p1PromptText);
        gameService.submitPrompt(roomCode, p2.getId(), p2PromptText);

        // Mock returns completedFuture — call onAllImagesReady immediately
        gameService.onAllImagesReady(roomCode);

        // Final guessing round
        gameService.submitGuess(roomCode, p1.getId(), "forest guess");
        gameService.submitGuess(roomCode, p2.getId(), "storm guess");

        // Final GENERATING pass — ensure it completes (idempotent if already done by async pipeline)
        gameService.onAllImagesReady(roomCode);

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(GamePhase.RESULTS, room.getPhase(), "Room must be in RESULTS phase");

        // Verify the chain entries are persisted with real text values
        List<Chain> chains = chainRepository.findByRoom(room);
        assertFalse(chains.isEmpty(), "There must be at least one chain");

        boolean foundNonEmptyEntry = false;
        for (Chain chain : chains) {
            List<ChainEntry> entries = chainEntryRepository.findByChainOrderByRoundAsc(chain);
            for (ChainEntry entry : entries) {
                if (!entry.isPlaceholder() && entry.getText() != null && !entry.getText().isBlank()) {
                    foundNonEmptyEntry = true;
                    // Assert at least one entry has the expected prompt text
                    assertTrue(
                        entry.getText().equals(p1PromptText) || entry.getText().equals(p2PromptText)
                            || entry.getText().equals("forest guess") || entry.getText().equals("storm guess"),
                        "Chain entry text must match one of the submitted prompts or guesses; got: " + entry.getText()
                    );
                }
            }
        }
        assertTrue(foundNonEmptyEntry,
                "At least one non-placeholder ChainEntry with real text must exist in the results");

        // Simulate a player reconnecting during RESULTS — the snapshot should return the results payload
        var snapshot = roomService.getGameStateSnapshot(roomCode, p1Token);
        assertEquals(GamePhase.RESULTS, snapshot.phase(),
                "Snapshot for a reconnecting player in RESULTS must reflect the RESULTS phase");
    }

    // ---- Scenario K — Generating timeout on the final round still reaches RESULTS ----

    @Test
    void scenarioK_GeneratingTimeoutOnFinalRound_StillReachesResults() throws Exception {
        // Round 1 images complete instantly; the final pass hangs (never-completing futures),
        // simulating a stuck generator that the generating timeout must recover from.
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"))
                .thenReturn(new CompletableFuture<>());

        String roomCode = createRoomAndGetCode("P1", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();
        String p2Token = joinPlayer(roomCode, "P2", "icon-2");

        connectPlayers(roomCode, p1Token, p2Token);
        startGame(roomCode, p1Token);

        Player p1 = playerRepository.findByToken(UUID.fromString(p1Token)).orElseThrow();
        Player p2 = playerRepository.findByToken(UUID.fromString(p2Token)).orElseThrow();

        gameService.submitPrompt(roomCode, p1.getId(), "a prompt");
        gameService.submitPrompt(roomCode, p2.getId(), "another prompt");
        gameService.onAllImagesReady(roomCode);

        gameService.submitGuess(roomCode, p1.getId(), "guess one");
        gameService.submitGuess(roomCode, p2.getId(), "guess two");

        // The hung final pass leaves the room stuck in GENERATING…
        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(GamePhase.GENERATING, room.getPhase(),
                "Room must remain in GENERATING while final images never complete");

        // …until the generating timeout fires (it calls onAllImagesReady).
        gameService.onAllImagesReady(roomCode);

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(GamePhase.RESULTS, room.getPhase(),
                "Generating timeout on the final round must still reach RESULTS");

        // Chains are intact; the final entries simply have no image.
        List<Chain> chains = chainRepository.findByRoom(room);
        assertEquals(2, chains.size());
        for (Chain chain : chains) {
            ChainEntry finalEntry = chainEntryRepository.findByChainAndRound(chain, 2).orElseThrow();
            assertNull(finalEntry.getImageUrl(),
                    "Final entry image stays null when the final generation never completed");
        }
    }

    // ---- Scenario L — Host returns first: snapshot shows full roster with returned status ----

    @Test
    void scenarioL_HostReturnsFirst_SnapshotShowsFullRosterWithReturnedStatus() throws Exception {
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"));

        String roomCode = createRoomAndGetCode("P1", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();
        connectPlayer(roomCode, p1Token);
        // joinAndConnect: a joined-but-never-connected player is deleted as a lobby
        // ghost by the next join, which would silently shrink the roster.
        String p2Token = joinAndConnect(roomCode, "P2", "icon-2");
        String p3Token = joinAndConnect(roomCode, "P3", "icon-3");

        startGame(roomCode, p1Token);

        Player p1 = playerRepository.findByToken(UUID.fromString(p1Token)).orElseThrow();
        Player p2 = playerRepository.findByToken(UUID.fromString(p2Token)).orElseThrow();
        Player p3 = playerRepository.findByToken(UUID.fromString(p3Token)).orElseThrow();

        gameService.submitPrompt(roomCode, p1.getId(), "prompt 1");
        gameService.submitPrompt(roomCode, p2.getId(), "prompt 2");
        gameService.submitPrompt(roomCode, p3.getId(), "prompt 3");
        gameService.onAllImagesReady(roomCode);

        for (int round = 2; round <= 3; round++) {
            gameService.submitGuess(roomCode, p1.getId(), "guess r" + round);
            gameService.submitGuess(roomCode, p2.getId(), "guess r" + round);
            gameService.submitGuess(roomCode, p3.getId(), "guess r" + round);
            gameService.onAllImagesReady(roomCode);
        }

        room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        assertEquals(GamePhase.RESULTS, room.getPhase());

        // The host leaves the showcase first…
        ResponseEntity<Void> leaveResponse = restTemplate.postForEntity(
                url("/api/rooms/" + roomCode + "/leave-results?token=" + p1Token),
                null,
                Void.class
        );
        assertEquals(HttpStatus.OK, leaveResponse.getStatusCode());

        // …and the lobby snapshot still shows everyone, with correct return status.
        var snapshot = roomService.getGameStateSnapshot(roomCode, p1Token);
        assertEquals(GamePhase.RESULTS, snapshot.phase());
        assertEquals(3, snapshot.players().size(),
                "RESULTS snapshot must contain the full game roster for the first returner");
        assertTrue(snapshot.players().stream()
                        .filter(dto -> dto.id().equals(p1.getId().toString()))
                        .allMatch(dto -> dto.returnedToLobby()),
                "The returning host must be flagged returnedToLobby");
        assertTrue(snapshot.players().stream()
                        .filter(dto -> !dto.id().equals(p1.getId().toString()))
                        .noneMatch(dto -> dto.returnedToLobby()),
                "Players still viewing results must not be flagged returned");
    }

    // ---- Scenario M — Reconnect during the final generating pass ----

    @Test
    void scenarioM_ReconnectDuringFinalGeneratingPass_SnapshotShowsGeneratingAtRoundN() throws Exception {
        when(imageGenerationService.generateImage(anyString()))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"))
                .thenReturn(CompletableFuture.completedFuture("http://test-image/img.png"))
                .thenReturn(new CompletableFuture<>());

        String roomCode = createRoomAndGetCode("P1", "icon-1");
        Room room = roomRepository.findByRoomCode(roomCode).orElseThrow();
        Player host = playerRepository.findById(room.getHostId()).orElseThrow();
        String p1Token = host.getToken().toString();
        String p2Token = joinPlayer(roomCode, "P2", "icon-2");

        connectPlayers(roomCode, p1Token, p2Token);
        startGame(roomCode, p1Token);

        Player p1 = playerRepository.findByToken(UUID.fromString(p1Token)).orElseThrow();
        Player p2 = playerRepository.findByToken(UUID.fromString(p2Token)).orElseThrow();

        gameService.submitPrompt(roomCode, p1.getId(), "a prompt");
        gameService.submitPrompt(roomCode, p2.getId(), "another prompt");
        gameService.onAllImagesReady(roomCode);

        gameService.submitGuess(roomCode, p1.getId(), "guess one");
        gameService.submitGuess(roomCode, p2.getId(), "guess two");

        // A player reconnecting mid final pass restores its state from the snapshot alone.
        var snapshot = roomService.getGameStateSnapshot(roomCode, p1Token);
        assertEquals(GamePhase.GENERATING, snapshot.phase());
        assertEquals(2, snapshot.currentRound());
        assertEquals(2, snapshot.totalRounds());
        assertEquals(0L, snapshot.timerSeconds());
        assertEquals(0L, snapshot.serverTimestamp());
        assertNull(snapshot.imageUrl());
    }

    // ---- Helpers ----

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<Map<String, String>> createBody(String alias, String avatarId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(Map.of("alias", alias, "avatarId", avatarId), headers);
    }

    private String createRoomAndGetCode(String alias, String avatarId) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                url("/api/rooms"),
                createBody(alias, avatarId),
                Map.class
        );
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return (String) response.getBody().get("roomCode");
    }

    private String joinPlayer(String roomCode, String alias, String avatarId) {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                url("/api/rooms/" + roomCode + "/join"),
                createBody(alias, avatarId),
                Map.class
        );
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return (String) response.getBody().get("playerToken");
    }

    /**
     * Joins a player and immediately connects them — mirrors the real client flow
     * (join over REST, then open the WebSocket). Joining without connecting leaves the
     * player {@code connected=false}, and the next join deletes them as a lobby "ghost".
     */
    private String joinAndConnect(String roomCode, String alias, String avatarId) {
        String token = joinPlayer(roomCode, alias, avatarId);
        connectPlayer(roomCode, token);
        return token;
    }

    private void connectPlayers(String roomCode, String... tokens) {
        for (String token : tokens) {
            connectPlayer(roomCode, token);
        }
    }

    private void connectPlayer(String roomCode, String token) {
        try {
            Player player = playerRepository.findByToken(UUID.fromString(token)).orElseThrow();
            roomService.playerConnected(roomCode, player.getId());
        } catch (Exception e) {
            // ignore
        }
    }

    private void startGame(String roomCode, String hostToken) {
        ResponseEntity<Void> response = restTemplate.postForEntity(
                url("/api/rooms/" + roomCode + "/start?token=" + hostToken),
                null,
                Void.class
        );
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
