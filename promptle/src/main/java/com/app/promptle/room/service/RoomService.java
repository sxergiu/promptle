package com.app.promptle.room.service;

import com.app.promptle.common.exception.GameException;
import com.app.promptle.game.dto.GameResultsEvent;
import com.app.promptle.game.dto.GameStateSnapshot;
import com.app.promptle.game.model.Chain;
import com.app.promptle.game.model.ChainEntry;
import com.app.promptle.game.model.GamePhase;
import com.app.promptle.game.repository.ChainEntryRepository;
import com.app.promptle.game.repository.ChainRepository;
import com.app.promptle.game.repository.RoundAssignmentRepository;
import com.app.promptle.game.service.RoundAssignmentService;
import com.app.promptle.image.api.ImageStorageService;
import com.app.promptle.room.dto.CreateRoomRequest;
import com.app.promptle.room.dto.JoinRoomRequest;
import com.app.promptle.room.dto.JoinRoomResponse;
import com.app.promptle.room.dto.PlayerDto;
import com.app.promptle.room.dto.RoomEvent;
import com.app.promptle.room.dto.RoomEventType;
import com.app.promptle.room.dto.RoomStateResponse;
import com.app.promptle.room.event.RoomApplicationEvent;
import com.app.promptle.room.mapper.RoomMapper;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import com.app.promptle.room.repository.PlayerRepository;
import com.app.promptle.room.repository.RoomRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
@Transactional
public class RoomService {

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 8;
    private static final int MAX_PLAYERS = 8;
    private static final int MAX_RETRIES = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${game.timer.prompting-seconds:60}")
    private long promptingSeconds = 60L;

    @Value("${game.timer.guessing-seconds:60}")
    private long guessingSeconds = 60L;

    private final RoomRepository roomRepository;
    private final PlayerRepository playerRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ImageStorageService imageStorageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final RoundAssignmentService roundAssignmentService;
    private final RoundAssignmentRepository roundAssignmentRepository;
    private final ChainEntryRepository chainEntryRepository;
    private final ChainRepository chainRepository;
    private final RoomMapper roomMapper;

    // Players who have left the RESULTS showcase and returned to the lobby, per room.
    // Once every connected player has returned, the room auto-resets to a fresh lobby.
    private final Map<String, Set<UUID>> resultsReturned = new ConcurrentHashMap<>();

    public RoomService(RoomRepository roomRepository,
                       PlayerRepository playerRepository,
                       ApplicationEventPublisher eventPublisher,
                       ImageStorageService imageStorageService,
                       SimpMessagingTemplate messagingTemplate,
                       RoundAssignmentService roundAssignmentService,
                       RoundAssignmentRepository roundAssignmentRepository,
                       ChainEntryRepository chainEntryRepository,
                       ChainRepository chainRepository,
                       RoomMapper roomMapper) {
        this.roomRepository = roomRepository;
        this.playerRepository = playerRepository;
        this.eventPublisher = eventPublisher;
        this.imageStorageService = imageStorageService;
        this.messagingTemplate = messagingTemplate;
        this.roundAssignmentService = roundAssignmentService;
        this.roundAssignmentRepository = roundAssignmentRepository;
        this.chainEntryRepository = chainEntryRepository;
        this.chainRepository = chainRepository;
        this.roomMapper = roomMapper;
    }

    public JoinRoomResponse createRoom(CreateRoomRequest request) {
        String roomCode = generateUniqueRoomCode();

        Room room = new Room();
        room.setRoomCode(roomCode);
        room.setPhase(GamePhase.LOBBY);
        room.setCurrentRound(0);
        room.setCreatedAt(Instant.now());
        room = roomRepository.save(room);

        Player host = new Player();
        host.setAlias(request.alias());
        host.setAvatarId(request.avatarId());
        host.setToken(UUID.randomUUID());
        host.setConnected(false);
        host.setRoom(room);
        host.setJoinedAt(Instant.now());
        host = playerRepository.save(host);

        room.setHostId(host.getId());
        roomRepository.save(room);

        return new JoinRoomResponse(host.getToken().toString(), roomCode, host.getId().toString());
    }

    public JoinRoomResponse joinRoom(String roomCode, JoinRoomRequest request) {
        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new GameException("Room not found: " + roomCode));

        if (room.getPhase() != GamePhase.LOBBY) {
            throw new GameException("Game already in progress");
        }

        if (playerRepository.findByRoomAndConnectedTrue(room).size() >= MAX_PLAYERS) {
            throw new GameException("Room is full");
        }

        // Clean up ghost players — disconnected during LOBBY means they left and aren't coming back.
        // Skip the host (they might reconnect via stored token).
        playerRepository.findByRoomAndConnectedFalse(room).stream()
                .filter(p -> !p.getId().equals(room.getHostId()))
                .forEach(playerRepository::delete);

        Player player = new Player();
        player.setAlias(request.alias());
        player.setAvatarId(request.avatarId());
        player.setToken(UUID.randomUUID());
        player.setConnected(false);
        player.setRoom(room);
        player.setJoinedAt(Instant.now());
        player = playerRepository.save(player);

        publishRoomEvent(roomCode, RoomEventType.PLAYER_JOINED, lobbyRoster(room), room.getHostId());

        return new JoinRoomResponse(player.getToken().toString(), roomCode, player.getId().toString());
    }

    public RoomStateResponse getRoomState(String roomCode) {
        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new GameException("Room not found: " + roomCode));
        List<Player> players = playerRepository.findByRoom(room);
        return roomMapper.toStateResponse(room, players);
    }

    public GameStateSnapshot getGameStateSnapshot(String roomCode, String playerToken) {
        UUID tokenUuid = UUID.fromString(playerToken);
        Player player = playerRepository.findByToken(tokenUuid)
                .orElseThrow(() -> new GameException("Player not found for token: " + playerToken));

        Room room = player.getRoom();
        GamePhase phase = room.getPhase();

        long timerSeconds;
        long serverTimestamp;
        String imageUrl = null;

        if (phase == GamePhase.LOBBY) {
            timerSeconds = 0L;
            serverTimestamp = 0L;
        } else if (phase == GamePhase.PROMPTING) {
            timerSeconds = promptingSeconds;
            serverTimestamp = room.getRoundStartedAt() != null ? room.getRoundStartedAt().toEpochMilli() : 0L;
        } else if (phase == GamePhase.GUESSING) {
            timerSeconds = guessingSeconds;
            serverTimestamp = room.getRoundStartedAt() != null ? room.getRoundStartedAt().toEpochMilli() : 0L;
            Chain chain = roundAssignmentService.getAssignedChain(room, player, room.getCurrentRound());
            Optional<ChainEntry> entry = chainEntryRepository.findByChainAndRound(chain, room.getCurrentRound() - 1);
            imageUrl = entry.map(ChainEntry::getImageUrl).orElse(null);
        } else {
            timerSeconds = 0L;
            serverTimestamp = 0L;
        }

        List<Player> snapshotPlayers;
        if (phase == GamePhase.LOBBY) {
            // In LOBBY, show connected players plus the current host (lobbyRoster keeps the
            // host visible across a brief WS drop). Always include the requesting player too,
            // even if their own WS handshake hasn't completed yet.
            List<Player> roster = lobbyRoster(room);
            snapshotPlayers = roster.stream().anyMatch(p -> p.getId().equals(player.getId()))
                    ? roster
                    : Stream.concat(roster.stream(), Stream.of(player)).toList();
        } else if (phase == GamePhase.RESULTS) {
            // Full game roster — players whose WS briefly drops while navigating back to
            // the lobby must not vanish from the roster of whoever returned first.
            snapshotPlayers = playerRepository.findByRoom(room);
        } else {
            List<Player> connectedPlayers = playerRepository.findByRoomAndConnectedTrue(room);
            // Always include the requesting player even if not yet connected (WS handshake hasn't fired yet)
            snapshotPlayers = connectedPlayers.stream().anyMatch(p -> p.getId().equals(player.getId()))
                    ? connectedPlayers
                    : Stream.concat(connectedPlayers.stream(), Stream.of(player)).toList();
        }
        GameStateSnapshot base = roomMapper.toSnapshot(room, snapshotPlayers, timerSeconds, serverTimestamp, imageUrl,
                returnedIds(roomCode));

        boolean hasSubmitted = computeHasSubmitted(player, room);
        int submittedCount = computeSubmittedCount(room);

        return new GameStateSnapshot(
                base.phase(),
                base.currentRound(),
                base.totalRounds(),
                base.timerSeconds(),
                base.serverTimestamp(),
                base.imageUrl(),
                hasSubmitted,
                submittedCount,
                base.players(),
                base.hostId()
        );
    }

    public void playerConnected(String roomCode, UUID playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new GameException("Player not found: " + playerId));

        player.setConnected(true);
        playerRepository.save(player);

        Room room = player.getRoom();
        List<Player> connectedPlayers = playerRepository.findByRoomAndConnectedTrue(room);
        // During RESULTS the lobby shows the frozen full roster with return status —
        // a connected-only list would make briefly-dropped returnees vanish from it.
        // In LOBBY, lobbyRoster keeps the host pinned even across a brief WS drop.
        publishRoomEvent(roomCode, RoomEventType.PLAYER_JOINED, rosterFor(room, connectedPlayers), room.getHostId());

        if (room.getPhase() == GamePhase.RESULTS) {
            GameResultsEvent resultsEvent = buildGameResultsEvent(room, connectedPlayers);
            messagingTemplate.convertAndSendToUser(
                    player.getId().toString(),
                    "/queue/game",
                    resultsEvent
            );
        }
    }

    public void playerDisconnected(String roomCode, UUID playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new GameException("Player not found: " + playerId));

        player.setConnected(false);
        playerRepository.save(player);

        Room room = player.getRoom();
        List<Player> connectedPlayers = playerRepository.findByRoomAndConnectedTrue(room);
        // Same full-roster rule as playerConnected: don't shrink the RESULTS-wait
        // roster in already-returned players' lobbies on a transient WS drop. In LOBBY,
        // lobbyRoster keeps the current host pinned (the hand-off below re-broadcasts
        // with the new host if the leaver was the host).
        publishRoomEvent(roomCode, RoomEventType.PLAYER_LEFT, rosterFor(room, connectedPlayers), room.getHostId());

        // Auto-reset when last player disconnects during a non-LOBBY phase
        // so new players can always join a room without the host having to manually reset
        if (connectedPlayers.isEmpty() && room.getPhase() != GamePhase.LOBBY) {
            performReset(room, false);
            return;
        }

        // A viewer disconnecting during RESULTS may leave the remaining connected
        // players all back in the lobby — reset just as if they'd returned.
        if (room.getPhase() == GamePhase.RESULTS) {
            resetIfAllReturned(room);
        }

        // Host reassignment — skip during RESULTS; brief disconnects during showcase must not transfer host
        if (room.getPhase() != GamePhase.RESULTS && room.getHostId() != null && room.getHostId().equals(player.getId())) {
            List<Player> otherConnected = connectedPlayers.stream()
                    .filter(p -> !p.getId().equals(playerId))
                    .sorted(Comparator.comparing(Player::getJoinedAt))
                    .toList();

            if (!otherConnected.isEmpty()) {
                Player newHost = otherConnected.get(0);
                room.setHostId(newHost.getId());
                roomRepository.save(room);
                publishRoomEvent(roomCode, RoomEventType.HOST_CHANGED, connectedPlayers, newHost.getId());
            }
        }
    }

    public void resetGame(String roomCode, String playerToken) {
        UUID tokenUuid = UUID.fromString(playerToken);
        playerRepository.findByToken(tokenUuid)
                .orElseThrow(() -> new GameException("Player not found for token: " + playerToken));

        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new GameException("Room not found: " + roomCode));

        if (room.getPhase() == GamePhase.LOBBY) {
            return; // already reset — idempotent
        }

        performReset(room, true);
    }

    /**
     * Marks a player as having left the RESULTS showcase and returned to the lobby.
     * Each player navigates back individually; the room itself only resets to a fresh
     * lobby once every connected player has returned (or disconnected).
     */
    public void markReturnedToLobby(String roomCode, String playerToken) {
        UUID tokenUuid = UUID.fromString(playerToken);
        Player player = playerRepository.findByToken(tokenUuid)
                .orElseThrow(() -> new GameException("Player not found for token: " + playerToken));

        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new GameException("Room not found: " + roomCode));

        if (room.getPhase() != GamePhase.RESULTS) {
            return; // nothing to return from
        }

        resultsReturned.computeIfAbsent(roomCode, k -> ConcurrentHashMap.newKeySet()).add(player.getId());
        if (!resetIfAllReturned(room)) {
            // Broadcast the FULL roster (not just connected players) so already-returned
            // players see the newcomer flip to "returned" even if someone's WS briefly
            // dropped during navigation. The all-returned case is superseded by GAME_RESET.
            publishRoomEvent(roomCode, RoomEventType.PLAYER_RETURNED,
                    playerRepository.findByRoom(room), room.getHostId());
        }
    }

    // ---- Private helpers ----

    /**
     * Picks the roster to broadcast for a phase given the already-fetched connected list:
     * the frozen full roster during RESULTS, the {@link #lobbyRoster(Room)} during LOBBY,
     * and the connected players otherwise.
     */
    private List<Player> rosterFor(Room room, List<Player> connectedPlayers) {
        return switch (room.getPhase()) {
            case RESULTS -> playerRepository.findByRoom(room);
            case LOBBY -> lobbyRoster(room);
            default -> connectedPlayers;
        };
    }

    /**
     * The LOBBY roster: connected players plus the <em>current</em> host even if their
     * socket is momentarily down. A mobile host backgrounding the tab to share the invite
     * must never disappear for players who join during that window, and a freshly created
     * host (connected=false until their WS handshake lands) must be visible immediately.
     * Only the current host is force-included, so a host who has truly left — and been
     * handed off to someone else — drops out naturally.
     */
    private List<Player> lobbyRoster(Room room) {
        List<Player> connected = playerRepository.findByRoomAndConnectedTrue(room);
        UUID hostId = room.getHostId();
        if (hostId == null || connected.stream().anyMatch(p -> p.getId().equals(hostId))) {
            return connected;
        }
        return playerRepository.findById(hostId)
                .map(host -> Stream.concat(connected.stream(), Stream.of(host))
                        .sorted(Comparator.comparing(Player::getJoinedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList())
                .orElse(connected);
    }

    /**
     * Resets the room to a fresh lobby once every connected player has returned from
     * RESULTS. Returns whether the reset happened.
     */
    private boolean resetIfAllReturned(Room room) {
        Set<UUID> returned = returnedIds(room.getRoomCode());
        List<Player> connected = playerRepository.findByRoomAndConnectedTrue(room);
        if (connected.isEmpty()) {
            return false; // the all-disconnected case is handled by performReset on disconnect
        }
        boolean allReturned = connected.stream().allMatch(p -> returned.contains(p.getId()));
        if (allReturned) {
            performReset(room, true);
            return true;
        }
        return false;
    }

    private Set<UUID> returnedIds(String roomCode) {
        return resultsReturned.getOrDefault(roomCode, Set.of());
    }

    /**
     * Wipes all game data, removes ghost players, and returns the room to LOBBY.
     * When {@code broadcast} is true a GAME_RESET event is published so connected
     * clients refresh; the silent variant is used when no clients remain.
     */
    private void performReset(Room room, boolean broadcast) {
        // Collect image URLs before deleting chain data
        List<Chain> chains = chainRepository.findByRoom(room);
        List<String> imageUrls = chains.stream()
                .flatMap(chain -> chainEntryRepository.findByChainOrderByRoundAsc(chain).stream())
                .map(ChainEntry::getImageUrl)
                .filter(url -> url != null)
                .toList();

        // Delete game data in dependency order
        roundAssignmentRepository.deleteAllByRoom(room);
        chainEntryRepository.deleteAllByChainIn(chains);
        chainRepository.deleteAllByRoom(room);

        // Delete stored images
        imageStorageService.deleteImages(imageUrls);

        // Remove ghost players (disconnected during lobby/game)
        playerRepository.deleteAll(playerRepository.findByRoomAndConnectedFalse(room));

        // Reset room to LOBBY
        room.setPhase(GamePhase.LOBBY);
        room.setCurrentRound(0);
        room.setTotalRounds(0);
        room.setRoundStartedAt(null);
        roomRepository.save(room);

        resultsReturned.remove(room.getRoomCode());

        if (broadcast) {
            List<Player> connectedPlayers = playerRepository.findByRoomAndConnectedTrue(room);
            publishRoomEvent(room.getRoomCode(), RoomEventType.GAME_RESET, connectedPlayers, room.getHostId());
        }
    }

    private String generateUniqueRoomCode() {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String code = randomCode();
            if (roomRepository.findByRoomCode(code).isEmpty()) {
                return code;
            }
        }
        throw new GameException("Failed to generate unique room code after " + MAX_RETRIES + " attempts");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    private void publishRoomEvent(String roomCode, RoomEventType type, List<Player> players, UUID hostId) {
        Set<UUID> returned = returnedIds(roomCode);
        List<PlayerDto> playerDtos = players.stream()
                .map(p -> roomMapper.toDto(p, returned))
                .toList();
        String hostIdStr = hostId != null ? hostId.toString() : null;
        RoomEvent roomEvent = new RoomEvent(type, playerDtos, hostIdStr);
        eventPublisher.publishEvent(new RoomApplicationEvent(roomCode, roomEvent));
    }

    private boolean computeHasSubmitted(Player player, Room room) {
        GamePhase phase = room.getPhase();
        if (phase == GamePhase.PROMPTING) {
            return chainEntryRepository.existsByChainOriginPlayerAndRoundAndIsPlaceholderFalse(player, room.getCurrentRound());
        } else if (phase == GamePhase.GUESSING) {
            Chain chain = roundAssignmentService.getAssignedChain(room, player, room.getCurrentRound());
            return chainEntryRepository.existsByChainAndRoundAndAuthorAndIsPlaceholderFalse(chain, room.getCurrentRound(), player);
        }
        return false;
    }

    private int computeSubmittedCount(Room room) {
        GamePhase phase = room.getPhase();
        if (phase == GamePhase.PROMPTING || phase == GamePhase.GUESSING) {
            List<Chain> chains = chainRepository.findByRoom(room);
            return (int) chainEntryRepository.countByChainInAndRound(chains, room.getCurrentRound());
        }
        return 0;
    }

    /**
     * Builds a GameResultsEvent by assembling chain entries for every chain in the room.
     * Used when a player reconnects during RESULTS phase to re-send the results payload.
     * Mirrors the chain&rarr;DTO assembly in GameService.transitionToResults().
     */
    private GameResultsEvent buildGameResultsEvent(Room room, List<Player> connectedPlayers) {
        List<Chain> chains = chainRepository.findByRoom(room);
        List<com.app.promptle.game.dto.ChainDto> chainDtos = chains.stream().map(chain -> {
            List<ChainEntry> entries = chainEntryRepository.findByChainOrderByRoundAsc(chain);
            List<com.app.promptle.game.dto.ChainEntryDto> entryDtos = entries.stream().map(e ->
                    new com.app.promptle.game.dto.ChainEntryDto(
                            e.getAuthor() != null ? e.getAuthor().getId().toString() : null,
                            e.getAuthor() != null ? e.getAuthor().getAvatarId() : null,
                            e.getText(),
                            e.getImageUrl(),
                            e.isPlaceholder()
                    )
            ).toList();
            return new com.app.promptle.game.dto.ChainDto(entryDtos);
        }).toList();
        return new GameResultsEvent(chainDtos);
    }
}
