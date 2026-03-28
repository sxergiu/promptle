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
import java.util.Optional;
import java.util.UUID;
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

        Player player = new Player();
        player.setAlias(request.alias());
        player.setAvatarId(request.avatarId());
        player.setToken(UUID.randomUUID());
        player.setConnected(false);
        player.setRoom(room);
        player.setJoinedAt(Instant.now());
        player = playerRepository.save(player);

        List<Player> connectedPlayers = playerRepository.findByRoomAndConnectedTrue(room);
        publishRoomEvent(roomCode, RoomEventType.PLAYER_JOINED, connectedPlayers, room.getHostId());

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
            // In LOBBY, include all joined players (connected or not) so the lobby roster is complete
            snapshotPlayers = playerRepository.findByRoom(room);
        } else {
            List<Player> connectedPlayers = playerRepository.findByRoomAndConnectedTrue(room);
            // Always include the requesting player even if not yet connected (WS handshake hasn't fired yet)
            snapshotPlayers = connectedPlayers.stream().anyMatch(p -> p.getId().equals(player.getId()))
                    ? connectedPlayers
                    : Stream.concat(connectedPlayers.stream(), Stream.of(player)).toList();
        }
        GameStateSnapshot base = roomMapper.toSnapshot(room, snapshotPlayers, timerSeconds, serverTimestamp, imageUrl);

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
        publishRoomEvent(roomCode, RoomEventType.PLAYER_JOINED, connectedPlayers, room.getHostId());

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
        publishRoomEvent(roomCode, RoomEventType.PLAYER_LEFT, connectedPlayers, room.getHostId());

        // Auto-reset when last player disconnects during a non-LOBBY phase
        // so new players can always join a room without the host having to manually reset
        if (connectedPlayers.isEmpty() && room.getPhase() != GamePhase.LOBBY) {
            autoResetRoom(room);
            return;
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

        // Broadcast GAME_RESET so all clients navigate back to lobby
        List<Player> connectedPlayers = playerRepository.findByRoomAndConnectedTrue(room);
        publishRoomEvent(roomCode, RoomEventType.GAME_RESET, connectedPlayers, room.getHostId());
    }

    // ---- Private helpers ----

    /**
     * Silently resets a room to LOBBY when all players have disconnected mid-game.
     * Deletes game data so new players can join with a clean slate.
     * No GAME_RESET event is broadcast (no clients are connected to receive it).
     */
    private void autoResetRoom(Room room) {
        List<Chain> chains = chainRepository.findByRoom(room);
        List<String> imageUrls = chains.stream()
                .flatMap(chain -> chainEntryRepository.findByChainOrderByRoundAsc(chain).stream())
                .map(ChainEntry::getImageUrl)
                .filter(url -> url != null)
                .toList();

        roundAssignmentRepository.deleteAllByRoom(room);
        chainEntryRepository.deleteAllByChainIn(chains);
        chainRepository.deleteAllByRoom(room);
        imageStorageService.deleteImages(imageUrls);

        playerRepository.deleteAll(playerRepository.findByRoomAndConnectedFalse(room));

        room.setPhase(GamePhase.LOBBY);
        room.setCurrentRound(0);
        room.setTotalRounds(0);
        room.setRoundStartedAt(null);
        roomRepository.save(room);
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
        List<PlayerDto> playerDtos = players.stream()
                .map(roomMapper::toDto)
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
     * Mirrors the assembly logic in GameService.endGuessingRound().
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
