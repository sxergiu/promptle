package com.app.promptle.game.service;

import com.app.promptle.common.exception.GameException;
import com.app.promptle.game.event.PhaseChangedApplicationEvent;
import com.app.promptle.game.model.Chain;
import com.app.promptle.game.model.GamePhase;
import com.app.promptle.game.repository.ChainEntryRepository;
import com.app.promptle.game.repository.ChainRepository;
import com.app.promptle.image.api.ImageGenerationService;
import com.app.promptle.room.dto.PlayerDto;
import com.app.promptle.room.dto.RoomEvent;
import com.app.promptle.room.dto.RoomEventType;
import com.app.promptle.room.event.RoomApplicationEvent;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import com.app.promptle.room.repository.PlayerRepository;
import com.app.promptle.room.repository.RoomRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Manages game lifecycle transitions.
 */
@Service
public class GameService {

    private final RoomRepository roomRepository;
    private final PlayerRepository playerRepository;
    private final ChainRepository chainRepository;
    private final ChainEntryRepository chainEntryRepository;
    private final RoundAssignmentService roundAssignmentService;
    private final TimerService timerService;
    private final ImageGenerationService imageGenerationService;
    private final ApplicationEventPublisher eventPublisher;
    private final long promptingSeconds;
    private final long guessingSeconds;

    public GameService(RoomRepository roomRepository,
                       PlayerRepository playerRepository,
                       ChainRepository chainRepository,
                       ChainEntryRepository chainEntryRepository,
                       RoundAssignmentService roundAssignmentService,
                       TimerService timerService,
                       ImageGenerationService imageGenerationService,
                       ApplicationEventPublisher eventPublisher,
                       @Value("${game.timer.prompting-seconds:60}") long promptingSeconds,
                       @Value("${game.timer.guessing-seconds:60}") long guessingSeconds) {
        this.roomRepository = roomRepository;
        this.playerRepository = playerRepository;
        this.chainRepository = chainRepository;
        this.chainEntryRepository = chainEntryRepository;
        this.roundAssignmentService = roundAssignmentService;
        this.timerService = timerService;
        this.imageGenerationService = imageGenerationService;
        this.eventPublisher = eventPublisher;
        this.promptingSeconds = promptingSeconds;
        this.guessingSeconds = guessingSeconds;
    }

    /**
     * Starts the game for the given room. The requesting player must be the host.
     */
    @Transactional
    public void startGame(String roomCode, UUID requestingPlayerId) {
        Room room = roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new GameException("Room not found: " + roomCode));

        if (!room.getHostId().equals(requestingPlayerId)) {
            throw new GameException("Not the host");
        }

        List<Player> connected = playerRepository.findByRoomAndConnectedTrue(room);

        if (connected.size() < 2 || connected.size() > 8) {
            throw new GameException("Invalid player count: " + connected.size());
        }

        room.setTotalRounds(connected.size());
        room.setCurrentRound(1);
        room.setPhase(GamePhase.PROMPTING);
        room.setRoundStartedAt(Instant.now());
        roomRepository.save(room);

        // Order players by joinedAt for deterministic chain creation
        List<Player> orderedPlayers = connected.stream()
                .sorted(Comparator.comparing(Player::getJoinedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // Create one Chain per player
        List<Chain> savedChains = new ArrayList<>();
        for (Player player : orderedPlayers) {
            Chain chain = new Chain();
            chain.setRoom(room);
            chain.setOriginPlayer(player);
            savedChains.add(chainRepository.save(chain));
        }

        roundAssignmentService.generateAssignments(room, orderedPlayers, savedChains);
        timerService.startRoundTimer(roomCode, 1, promptingSeconds);

        // Build player DTOs inline (no RoomMapper dependency in constructor)
        List<PlayerDto> playerDtos = connected.stream()
                .map(p -> new PlayerDto(p.getId().toString(), p.getAlias(), p.getAvatarId(), p.isConnected()))
                .toList();

        eventPublisher.publishEvent(new RoomApplicationEvent(roomCode,
                new RoomEvent(RoomEventType.GAME_STARTED, playerDtos, room.getHostId().toString())));

        eventPublisher.publishEvent(new PhaseChangedApplicationEvent(
                roomCode, GamePhase.PROMPTING, 1, connected.size(), promptingSeconds, Instant.now().toEpochMilli()));
    }

    /**
     * Submits a prompt for the current round. Fully implemented in Chunk 8.
     */
    public void submitPrompt(String roomCode, UUID playerId, String text) {
        throw new UnsupportedOperationException("Not yet implemented — stub");
    }

    /**
     * Submits a guess for the current round. Fully implemented in Chunk 8.
     */
    public void submitGuess(String roomCode, UUID playerId, String text) {
        throw new UnsupportedOperationException("Not yet implemented — stub");
    }

    /**
     * Called when a round timer expires. Fully implemented in Chunk 13.
     */
    public void onRoundTimerExpired(String roomCode, int round) {
        throw new UnsupportedOperationException("Not yet implemented — stub");
    }

    /**
     * Advances the showcase to the next chain. Fully implemented in Chunk 12.
     */
    public void advanceShowcase(String roomCode, UUID playerId) {
        throw new UnsupportedOperationException("Not yet implemented — stub");
    }

    /**
     * Called when all images are ready. Fully implemented in Chunk 9.
     */
    public void onAllImagesReady(String roomCode) {
        throw new UnsupportedOperationException("Not yet implemented — stub");
    }
}
