package com.app.promptle.room.service;

import com.app.promptle.common.exception.GameException;
import com.app.promptle.game.dto.GameResultsEvent;
import com.app.promptle.game.dto.GameStateSnapshot;
import com.app.promptle.game.model.Chain;
import com.app.promptle.game.model.ChainEntry;
import com.app.promptle.game.model.GamePhase;
import com.app.promptle.game.repository.ChainEntryRepository;
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
    private final ChainEntryRepository chainEntryRepository;
    private final RoomMapper roomMapper;

    public RoomService(RoomRepository roomRepository,
                       PlayerRepository playerRepository,
                       ApplicationEventPublisher eventPublisher,
                       ImageStorageService imageStorageService,
                       SimpMessagingTemplate messagingTemplate,
                       RoundAssignmentService roundAssignmentService,
                       ChainEntryRepository chainEntryRepository,
                       RoomMapper roomMapper) {
        this.roomRepository = roomRepository;
        this.playerRepository = playerRepository;
        this.eventPublisher = eventPublisher;
        this.imageStorageService = imageStorageService;
        this.messagingTemplate = messagingTemplate;
        this.roundAssignmentService = roundAssignmentService;
        this.chainEntryRepository = chainEntryRepository;
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

        List<Player> connected = playerRepository.findByRoomAndConnectedTrue(room);
        if (connected.size() >= MAX_PLAYERS) {
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

        List<Player> allPlayers = playerRepository.findByRoom(room);
        publishRoomEvent(roomCode, RoomEventType.PLAYER_JOINED, allPlayers, room.getHostId());

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

        List<Player> allPlayers = playerRepository.findByRoom(room);
        GameStateSnapshot base = roomMapper.toSnapshot(room, allPlayers, timerSeconds, serverTimestamp, imageUrl);

        boolean hasSubmitted = computeHasSubmitted(player, room);

        return new GameStateSnapshot(
                base.phase(),
                base.currentRound(),
                base.totalRounds(),
                base.timerSeconds(),
                base.serverTimestamp(),
                base.imageUrl(),
                hasSubmitted,
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

        // Host reassignment
        if (room.getHostId() != null && room.getHostId().equals(player.getId())) {
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

        // Cleanup on all disconnected in RESULTS phase
        if (room.getPhase() == GamePhase.RESULTS && connectedPlayers.isEmpty()) {
            imageStorageService.deleteGame(room.getId().toString());
        }
    }

    // ---- Private helpers ----

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
        // hasSubmitted is true if a non-placeholder ChainEntry exists for this player in the current round
        // This is fully resolved in later chunks when chain assignment is complete.
        // For now, return false as the base case.
        return false;
    }

    /**
     * Builds a GameResultsEvent by assembling chain entries for each player.
     * Stub implementation — fully realized in Chunk 12.
     */
    private GameResultsEvent buildGameResultsEvent(Room room, List<Player> players) {
        List<com.app.promptle.game.dto.ChainDto> chains = players.stream().map(p -> {
            Chain assignedChain = roundAssignmentService.getAssignedChain(room, p, room.getCurrentRound());
            List<ChainEntry> entries = chainEntryRepository.findByChainOrderByRoundAsc(assignedChain);
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
        return new GameResultsEvent(chains);
    }
}
