package com.app.promptle.room.service;

import com.app.promptle.common.exception.GameException;
import com.app.promptle.game.dto.GameStateSnapshot;
import com.app.promptle.game.event.GameResultsApplicationEvent;
import com.app.promptle.game.model.Chain;
import com.app.promptle.game.model.ChainEntry;
import com.app.promptle.game.model.GamePhase;
import com.app.promptle.game.model.RoundAssignment;
import com.app.promptle.game.repository.ChainEntryRepository;
import com.app.promptle.game.repository.ChainRepository;
import com.app.promptle.game.service.RoundAssignmentService;
import com.app.promptle.image.api.ImageStorageService;
import com.app.promptle.room.dto.JoinRoomRequest;
import com.app.promptle.room.dto.JoinRoomResponse;
import com.app.promptle.room.dto.CreateRoomRequest;
import com.app.promptle.room.dto.RoomStateResponse;
import com.app.promptle.room.event.RoomApplicationEvent;
import com.app.promptle.room.mapper.RoomMapper;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private PlayerRepository playerRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ImageStorageService imageStorageService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private RoundAssignmentService roundAssignmentService;

    @Mock
    private ChainEntryRepository chainEntryRepository;

    @Mock
    private ChainRepository chainRepository;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(
                roomRepository,
                playerRepository,
                eventPublisher,
                imageStorageService,
                messagingTemplate,
                roundAssignmentService,
                chainEntryRepository,
                chainRepository,
                new RoomMapper()
        );
    }

    // ---- createRoom ----

    @Test
    void createRoom_GeneratesUniqueRoomCode_EightAlphanumericChars() {
        when(roomRepository.findByRoomCode(anyString())).thenReturn(Optional.empty());
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> {
            Room r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(playerRepository.save(any(Player.class))).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        JoinRoomResponse response = roomService.createRoom(new CreateRoomRequest("Alice", "icon-1"));

        assertNotNull(response);
        String code = response.roomCode();
        assertNotNull(code);
        assertEquals(8, code.length());
        assertTrue(code.matches("[A-Z0-9]{8}"), "Room code must be 8-char alphanumeric but was: " + code);
    }

    @Test
    void createRoom_ReturnsString_NotEntity() {
        when(roomRepository.findByRoomCode(anyString())).thenReturn(Optional.empty());
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> {
            Room r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(playerRepository.save(any(Player.class))).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        Object result = roomService.createRoom(new CreateRoomRequest("Alice", "icon-1"));

        assertInstanceOf(JoinRoomResponse.class, result);
    }

    @Test
    void createRoom_RetriesOnCollision_UsesSecondCode() {
        // First code collides, second is fresh
        when(roomRepository.findByRoomCode(anyString()))
                .thenReturn(Optional.of(new Room()))
                .thenReturn(Optional.empty());
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> {
            Room r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(playerRepository.save(any(Player.class))).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        roomService.createRoom(new CreateRoomRequest("Alice", "icon-1"));

        verify(roomRepository, times(2)).findByRoomCode(anyString());
    }

    @Test
    void createRoom_HostPlayerSaved_WithConnectedFalseAndNonNullToken() {
        when(roomRepository.findByRoomCode(anyString())).thenReturn(Optional.empty());
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> {
            Room r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        ArgumentCaptor<Player> playerCaptor = ArgumentCaptor.forClass(Player.class);
        when(playerRepository.save(playerCaptor.capture())).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        roomService.createRoom(new CreateRoomRequest("Alice", "icon-1"));

        Player saved = playerCaptor.getValue();
        assertFalse(saved.isConnected());
        assertNotNull(saved.getToken());
    }

    // ---- joinRoom ----

    @Test
    void joinRoom_HappyPath_ReturnsResponseWithAllFields() {
        Room room = buildLobbyRoom("ABCD1234");
        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of());
        when(playerRepository.save(any(Player.class))).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        JoinRoomResponse response = roomService.joinRoom("ABCD1234", new JoinRoomRequest("Bob", "icon-2"));

        assertNotNull(response.playerToken());
        assertNotNull(response.roomCode());
        assertNotNull(response.playerId());
    }

    @Test
    void joinRoom_ThrowsGameException_WhenRoomNotFound() {
        when(roomRepository.findByRoomCode("NOTHERE")).thenReturn(Optional.empty());

        assertThrows(GameException.class,
                () -> roomService.joinRoom("NOTHERE", new JoinRoomRequest("Bob", "icon-2")));
    }

    @Test
    void joinRoom_ThrowsGameException_WhenPhaseNotLobby() {
        Room room = buildLobbyRoom("ABCD1234");
        room.setPhase(GamePhase.PROMPTING);
        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));

        assertThrows(GameException.class,
                () -> roomService.joinRoom("ABCD1234", new JoinRoomRequest("Bob", "icon-2")));
    }

    @Test
    void joinRoom_ThrowsGameException_WhenRoomFull() {
        Room room = buildLobbyRoom("ABCD1234");
        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        List<Player> eightPlayers = buildConnectedPlayers(8, room);
        when(playerRepository.findByRoom(room)).thenReturn(eightPlayers);

        assertThrows(GameException.class,
                () -> roomService.joinRoom("ABCD1234", new JoinRoomRequest("Bob", "icon-2")));
    }

    @Test
    void joinRoom_NewPlayer_SavedWithConnectedFalseAndNonNullToken() {
        Room room = buildLobbyRoom("ABCD1234");
        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of());

        ArgumentCaptor<Player> captor = ArgumentCaptor.forClass(Player.class);
        when(playerRepository.save(captor.capture())).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        roomService.joinRoom("ABCD1234", new JoinRoomRequest("Bob", "icon-2"));

        Player saved = captor.getValue();
        assertFalse(saved.isConnected());
        assertNotNull(saved.getToken());
    }

    @Test
    void joinRoom_PublishesPlayerJoinedEvent() {
        Room room = buildLobbyRoom("ABCD1234");
        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of());
        when(playerRepository.save(any(Player.class))).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        roomService.joinRoom("ABCD1234", new JoinRoomRequest("Bob", "icon-2"));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        boolean hasPlayerJoined = eventCaptor.getAllValues().stream()
                .filter(e -> e instanceof RoomApplicationEvent)
                .map(e -> (RoomApplicationEvent) e)
                .anyMatch(e -> e.payload() != null &&
                        "PLAYER_JOINED".equals(e.payload().type() != null ? e.payload().type().toString() : null));
        assertTrue(hasPlayerJoined, "Expected PLAYER_JOINED event to be published");
    }

    @Test
    void joinRoom_PlayerAliasAndAvatarSetFromRequest() {
        Room room = buildLobbyRoom("ABCD1234");
        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of());

        ArgumentCaptor<Player> captor = ArgumentCaptor.forClass(Player.class);
        when(playerRepository.save(captor.capture())).thenAnswer(inv -> {
            Player p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        roomService.joinRoom("ABCD1234", new JoinRoomRequest("Bob", "icon-7"));

        Player saved = captor.getValue();
        assertEquals("Bob", saved.getAlias());
        assertEquals("icon-7", saved.getAvatarId());
    }

    // ---- getRoomState ----

    @Test
    void getRoomState_HappyPath_ReturnsPopulatedResponse() {
        UUID hostId = UUID.randomUUID();
        Room room = buildLobbyRoom("ABCD1234");
        room.setHostId(hostId);
        room.setTotalRounds(2);
        when(roomRepository.findByRoomCode("ABCD1234")).thenReturn(Optional.of(room));
        when(playerRepository.findByRoom(room)).thenReturn(List.of());

        RoomStateResponse response = roomService.getRoomState("ABCD1234");

        assertEquals("ABCD1234", response.roomCode());
        assertEquals(GamePhase.LOBBY, response.phase());
        assertEquals(hostId.toString(), response.hostId());
    }

    @Test
    void getRoomState_ThrowsGameException_WhenRoomNotFound() {
        when(roomRepository.findByRoomCode("NOTHERE")).thenReturn(Optional.empty());

        assertThrows(GameException.class, () -> roomService.getRoomState("NOTHERE"));
    }

    // ---- getGameStateSnapshot ----

    @Test
    void getGameStateSnapshot_LobbyPhase_TimerFieldsAreZero() {
        Room room = buildLobbyRoom("ABCD1234");
        Player player = buildPlayer(room);
        player.setToken(UUID.randomUUID());
        String token = player.getToken().toString();
        when(playerRepository.findByToken(player.getToken())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoom(room)).thenReturn(List.of(player));

        GameStateSnapshot snapshot = roomService.getGameStateSnapshot("ABCD1234", token);

        assertEquals(0L, snapshot.timerSeconds());
        assertEquals(0L, snapshot.serverTimestamp());
    }

    @Test
    void getGameStateSnapshot_PromptingPhase_TimerFieldsMatchConfig() {
        Room room = buildRoomWithPhase("ABCD1234", GamePhase.PROMPTING);
        room.setRoundStartedAt(Instant.ofEpochMilli(1700000000000L));
        Player player = buildPlayer(room);
        when(playerRepository.findByToken(player.getToken())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));

        GameStateSnapshot snapshot = roomService.getGameStateSnapshot("ABCD1234", player.getToken().toString());

        assertTrue(snapshot.timerSeconds() > 0, "Prompting timer should be non-zero");
        assertEquals(1700000000000L, snapshot.serverTimestamp());
    }

    @Test
    void getGameStateSnapshot_GuessingPhase_TimerAndImageUrlSet() {
        Room room = buildRoomWithPhase("ABCD1234", GamePhase.GUESSING);
        room.setCurrentRound(2);
        room.setRoundStartedAt(Instant.ofEpochMilli(1700000000000L));
        Player player = buildPlayer(room);

        Chain chain = new Chain();
        chain.setId(UUID.randomUUID());

        ChainEntry entry = new ChainEntry();
        entry.setImageUrl("/api/images/game/img1");

        when(playerRepository.findByToken(player.getToken())).thenReturn(Optional.of(player));
        when(roundAssignmentService.getAssignedChain(room, player, 2)).thenReturn(chain);
        when(chainEntryRepository.findByChainAndRound(chain, 1)).thenReturn(Optional.of(entry));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));

        GameStateSnapshot snapshot = roomService.getGameStateSnapshot("ABCD1234", player.getToken().toString());

        assertTrue(snapshot.timerSeconds() > 0, "Guessing timer should be non-zero");
        assertEquals(1700000000000L, snapshot.serverTimestamp());
        assertNotNull(snapshot.imageUrl());
        assertEquals("/api/images/game/img1", snapshot.imageUrl());
    }

    @Test
    void getGameStateSnapshot_NonGuessingPhase_ImageUrlIsNull() {
        Room room = buildRoomWithPhase("ABCD1234", GamePhase.PROMPTING);
        room.setRoundStartedAt(Instant.now());
        Player player = buildPlayer(room);
        when(playerRepository.findByToken(player.getToken())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));

        GameStateSnapshot snapshot = roomService.getGameStateSnapshot("ABCD1234", player.getToken().toString());

        assertNull(snapshot.imageUrl());
    }

    @Test
    void getGameStateSnapshot_ThrowsGameException_WhenTokenInvalid() {
        when(playerRepository.findByToken(any(UUID.class))).thenReturn(Optional.empty());

        assertThrows(GameException.class,
                () -> roomService.getGameStateSnapshot("ABCD1234", UUID.randomUUID().toString()));
    }

    @Test
    void getGameStateSnapshot_prompting_returnsCorrectSubmittedCount() {
        Room room = buildRoomWithPhase("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        room.setRoundStartedAt(Instant.now());
        Player player = buildPlayer(room);

        Chain chain1 = new Chain();
        chain1.setId(UUID.randomUUID());
        Chain chain2 = new Chain();
        chain2.setId(UUID.randomUUID());
        List<Chain> chains = List.of(chain1, chain2);

        when(playerRepository.findByToken(player.getToken())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainRepository.findByRoom(room)).thenReturn(chains);
        when(chainEntryRepository.countByChainInAndRound(chains, 1)).thenReturn(1L);

        GameStateSnapshot snapshot = roomService.getGameStateSnapshot("ABCD1234", player.getToken().toString());

        assertEquals(1, snapshot.submittedCount());
    }

    @Test
    void getGameStateSnapshot_prompting_hasSubmittedTrue_whenPlayerAlreadySubmitted() {
        Room room = buildRoomWithPhase("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        room.setRoundStartedAt(Instant.now());
        Player player = buildPlayer(room);

        when(playerRepository.findByToken(player.getToken())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainRepository.findByRoom(room)).thenReturn(List.of());
        when(chainEntryRepository.existsByChainOriginPlayerAndRoundAndIsPlaceholderFalse(player, 1)).thenReturn(true);

        GameStateSnapshot snapshot = roomService.getGameStateSnapshot("ABCD1234", player.getToken().toString());

        assertTrue(snapshot.hasSubmitted());
    }

    @Test
    void getGameStateSnapshot_prompting_hasSubmittedFalse_whenPlayerHasNotSubmitted() {
        Room room = buildRoomWithPhase("ABCD1234", GamePhase.PROMPTING);
        room.setCurrentRound(1);
        room.setRoundStartedAt(Instant.now());
        Player player = buildPlayer(room);

        when(playerRepository.findByToken(player.getToken())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainRepository.findByRoom(room)).thenReturn(List.of());
        when(chainEntryRepository.existsByChainOriginPlayerAndRoundAndIsPlaceholderFalse(player, 1)).thenReturn(false);

        GameStateSnapshot snapshot = roomService.getGameStateSnapshot("ABCD1234", player.getToken().toString());

        assertFalse(snapshot.hasSubmitted());
    }

    @Test
    void getGameStateSnapshot_guessing_hasSubmittedTrue_whenPlayerAlreadyGuessed() {
        Room room = buildRoomWithPhase("ABCD1234", GamePhase.GUESSING);
        room.setCurrentRound(2);
        room.setRoundStartedAt(Instant.now());
        Player player = buildPlayer(room);

        Chain assignedChain = new Chain();
        assignedChain.setId(UUID.randomUUID());

        ChainEntry prevEntry = new ChainEntry();
        prevEntry.setImageUrl("/api/images/game/img1");

        when(playerRepository.findByToken(player.getToken())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(roundAssignmentService.getAssignedChain(room, player, 2)).thenReturn(assignedChain);
        when(chainEntryRepository.findByChainAndRound(assignedChain, 1)).thenReturn(Optional.of(prevEntry));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(assignedChain));
        when(chainEntryRepository.countByChainInAndRound(List.of(assignedChain), 2)).thenReturn(1L);
        when(chainEntryRepository.existsByChainAndRoundAndAuthorAndIsPlaceholderFalse(assignedChain, 2, player)).thenReturn(true);

        GameStateSnapshot snapshot = roomService.getGameStateSnapshot("ABCD1234", player.getToken().toString());

        assertTrue(snapshot.hasSubmitted());
    }

    @Test
    void getGameStateSnapshot_lobby_submittedCountIsZero() {
        Room room = buildLobbyRoom("ABCD1234");
        Player player = buildPlayer(room);
        when(playerRepository.findByToken(player.getToken())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoom(room)).thenReturn(List.of(player));

        GameStateSnapshot snapshot = roomService.getGameStateSnapshot("ABCD1234", player.getToken().toString());

        assertEquals(0, snapshot.submittedCount());
    }

    // ---- playerConnected ----

    @Test
    void playerConnected_SetsConnectedTrueAndSavesPlayer() {
        Room room = buildLobbyRoom("ABCD1234");
        Player player = buildPlayer(room);
        UUID playerId = player.getId();
        player.setConnected(false);

        when(playerRepository.findById(playerId)).thenReturn(Optional.of(player));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));

        roomService.playerConnected("ABCD1234", playerId);

        ArgumentCaptor<Player> captor = ArgumentCaptor.forClass(Player.class);
        verify(playerRepository).save(captor.capture());
        assertTrue(captor.getValue().isConnected());
    }

    @Test
    void playerConnected_PublishesPlayerJoinedEvent() {
        Room room = buildLobbyRoom("ABCD1234");
        Player player = buildPlayer(room);
        when(playerRepository.findById(player.getId())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));

        roomService.playerConnected("ABCD1234", player.getId());

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());
        boolean hasJoined = eventCaptor.getAllValues().stream()
                .filter(e -> e instanceof RoomApplicationEvent)
                .map(e -> (RoomApplicationEvent) e)
                .anyMatch(e -> e.payload() != null &&
                        "PLAYER_JOINED".equals(e.payload().type() != null ? e.payload().type().toString() : null));
        assertTrue(hasJoined);
    }

    @Test
    void playerConnected_ResultsPhase_SendsGameResultsEventToPlayer() {
        Room room = buildRoomWithPhase("ABCD1234", GamePhase.RESULTS);
        UUID roomId = UUID.randomUUID();
        room.setId(roomId);
        Player player = buildPlayer(room);

        Chain chain = new Chain();
        chain.setId(UUID.randomUUID());
        chain.setRoom(room);

        when(playerRepository.findById(player.getId())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(player));
        when(chainRepository.findByRoom(room)).thenReturn(List.of(chain));
        when(chainEntryRepository.findByChainOrderByRoundAsc(chain)).thenReturn(List.of());

        roomService.playerConnected("ABCD1234", player.getId());

        verify(messagingTemplate).convertAndSendToUser(
                eq(player.getId().toString()),
                eq("/queue/game"),
                any()
        );
    }

    // ---- playerDisconnected ----

    @Test
    void playerDisconnected_SetsConnectedFalseAndSavesPlayer() {
        Room room = buildLobbyRoom("ABCD1234");
        Player player = buildPlayer(room);
        player.setConnected(true);
        when(playerRepository.findById(player.getId())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of());

        roomService.playerDisconnected("ABCD1234", player.getId());

        ArgumentCaptor<Player> captor = ArgumentCaptor.forClass(Player.class);
        verify(playerRepository).save(captor.capture());
        assertFalse(captor.getValue().isConnected());
    }

    @Test
    void playerDisconnected_PublishesPlayerLeftEvent() {
        Room room = buildLobbyRoom("ABCD1234");
        Player player = buildPlayer(room);
        when(playerRepository.findById(player.getId())).thenReturn(Optional.of(player));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of());

        roomService.playerDisconnected("ABCD1234", player.getId());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        boolean hasLeft = captor.getAllValues().stream()
                .filter(e -> e instanceof RoomApplicationEvent)
                .map(e -> (RoomApplicationEvent) e)
                .anyMatch(e -> e.payload() != null &&
                        "PLAYER_LEFT".equals(e.payload().type() != null ? e.payload().type().toString() : null));
        assertTrue(hasLeft);
    }

    @Test
    void playerDisconnected_HostDisconnects_ReassignsHostToFirstOtherConnectedPlayer() {
        UUID hostId = UUID.randomUUID();
        Room room = buildLobbyRoom("ABCD1234");
        room.setId(UUID.randomUUID());
        room.setHostId(hostId);

        Player host = buildPlayerWithId(hostId, room);
        host.setConnected(true);
        host.setJoinedAt(Instant.now().minusSeconds(100));

        UUID otherId = UUID.randomUUID();
        Player other = buildPlayerWithId(otherId, room);
        other.setConnected(true);
        other.setJoinedAt(Instant.now().minusSeconds(50));

        when(playerRepository.findById(hostId)).thenReturn(Optional.of(host));
        // After host is saved with connected=false, only 'other' remains connected
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(other));

        roomService.playerDisconnected("ABCD1234", hostId);

        verify(roomRepository).save(argThat(r -> otherId.equals(r.getHostId())));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        boolean hasHostChanged = captor.getAllValues().stream()
                .filter(e -> e instanceof RoomApplicationEvent)
                .map(e -> (RoomApplicationEvent) e)
                .anyMatch(e -> e.payload() != null &&
                        "HOST_CHANGED".equals(e.payload().type() != null ? e.payload().type().toString() : null));
        assertTrue(hasHostChanged);
    }

    @Test
    void playerDisconnected_HostDisconnects_NoOtherPlayers_DoesNotPublishHostChanged() {
        UUID hostId = UUID.randomUUID();
        Room room = buildLobbyRoom("ABCD1234");
        room.setHostId(hostId);
        Player host = buildPlayerWithId(hostId, room);
        host.setConnected(true);

        when(playerRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of());

        roomService.playerDisconnected("ABCD1234", hostId);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        boolean hasHostChanged = captor.getAllValues().stream()
                .filter(e -> e instanceof RoomApplicationEvent)
                .map(e -> (RoomApplicationEvent) e)
                .anyMatch(e -> e.payload() != null &&
                        "HOST_CHANGED".equals(e.payload().type() != null ? e.payload().type().toString() : null));
        assertFalse(hasHostChanged);
    }

    @Test
    void playerDisconnected_ResultsPhase_AllDisconnected_CallsDeleteGame() {
        UUID roomId = UUID.randomUUID();
        Room room = buildRoomWithPhase("ABCD1234", GamePhase.RESULTS);
        room.setId(roomId);

        Player p1 = buildPlayer(room);
        p1.setConnected(true);
        Player p2 = buildPlayer(room);
        p2.setConnected(false);

        when(playerRepository.findById(p1.getId())).thenReturn(Optional.of(p1));
        // After p1 is saved with connected=false, no connected players remain
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of());

        roomService.playerDisconnected("ABCD1234", p1.getId());

        verify(imageStorageService).deleteGame(roomId.toString());
    }

    @Test
    void playerDisconnected_ResultsPhase_PlayersRemain_DoesNotCallDeleteGame() {
        UUID roomId = UUID.randomUUID();
        Room room = buildRoomWithPhase("ABCD1234", GamePhase.RESULTS);
        room.setId(roomId);

        Player p1 = buildPlayer(room);
        p1.setConnected(true);
        Player p2 = buildPlayer(room);
        p2.setConnected(true);

        when(playerRepository.findById(p1.getId())).thenReturn(Optional.of(p1));
        // After p1 disconnects, p2 still remains connected
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of(p2));

        roomService.playerDisconnected("ABCD1234", p1.getId());

        verify(imageStorageService, never()).deleteGame(anyString());
    }

    @Test
    void playerDisconnected_OutsideResultsPhase_DoesNotCallDeleteGame() {
        UUID roomId = UUID.randomUUID();
        Room room = buildRoomWithPhase("ABCD1234", GamePhase.GUESSING);
        room.setId(roomId);

        Player p1 = buildPlayer(room);
        p1.setConnected(true);

        when(playerRepository.findById(p1.getId())).thenReturn(Optional.of(p1));
        when(playerRepository.findByRoomAndConnectedTrue(room)).thenReturn(List.of());

        roomService.playerDisconnected("ABCD1234", p1.getId());

        verify(imageStorageService, never()).deleteGame(anyString());
    }

    // ---- Helpers ----

    private Room buildLobbyRoom(String code) {
        Room room = new Room();
        room.setId(UUID.randomUUID());
        room.setRoomCode(code);
        room.setPhase(GamePhase.LOBBY);
        room.setCurrentRound(0);
        room.setTotalRounds(0);
        room.setHostId(UUID.randomUUID());
        return room;
    }

    private Room buildRoomWithPhase(String code, GamePhase phase) {
        Room room = new Room();
        room.setId(UUID.randomUUID());
        room.setRoomCode(code);
        room.setPhase(phase);
        room.setCurrentRound(1);
        room.setTotalRounds(2);
        room.setHostId(UUID.randomUUID());
        return room;
    }

    private Player buildPlayer(Room room) {
        Player p = new Player();
        p.setId(UUID.randomUUID());
        p.setToken(UUID.randomUUID());
        p.setAlias("Player");
        p.setAvatarId("icon-1");
        p.setRoom(room);
        p.setConnected(false);
        p.setJoinedAt(Instant.now());
        return p;
    }

    private Player buildPlayerWithId(UUID id, Room room) {
        Player p = buildPlayer(room);
        p.setId(id);
        return p;
    }

    private List<Player> buildConnectedPlayers(int count, Room room) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> {
            Player p = buildPlayer(room);
            p.setConnected(true);
            return p;
        }).toList();
    }
}
