package com.app.promptle.game.service;

import com.app.promptle.common.exception.GameException;
import com.app.promptle.game.dto.*;
import com.app.promptle.game.event.*;
import com.app.promptle.game.model.*;
import com.app.promptle.game.repository.*;
import com.app.promptle.image.api.ImageGenerationService;
import com.app.promptle.room.dto.PlayerDto;
import com.app.promptle.room.dto.RoomEvent;
import com.app.promptle.room.dto.RoomEventType;
import com.app.promptle.room.event.RoomApplicationEvent;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import com.app.promptle.room.repository.PlayerRepository;
import com.app.promptle.room.repository.RoomRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

    // In-memory showcase counters per room (reset on new game not needed for chunk 8)
    private final Map<String, Integer> showcaseCounters = new ConcurrentHashMap<>();

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

    @Transactional
    public void startGame(String roomCode, UUID requestingPlayerId) {
        Room room = getRoom(roomCode);

        if (room.getPhase() != GamePhase.LOBBY) {
            throw new GameException("Game already started");
        }

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

        List<Player> orderedPlayers = connected.stream()
                .sorted(Comparator.comparing(Player::getJoinedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<Chain> savedChains = new ArrayList<>();
        for (Player player : orderedPlayers) {
            Chain chain = new Chain();
            chain.setRoom(room);
            chain.setOriginPlayer(player);
            savedChains.add(chainRepository.save(chain));
        }

        roundAssignmentService.generateAssignments(room, orderedPlayers, savedChains);
        timerService.startRoundTimer(roomCode, 1, promptingSeconds);

        List<PlayerDto> playerDtos = connected.stream()
                .map(p -> new PlayerDto(p.getId().toString(), p.getAlias(), p.getAvatarId(), p.isConnected()))
                .toList();

        eventPublisher.publishEvent(new RoomApplicationEvent(roomCode,
                new RoomEvent(RoomEventType.GAME_STARTED, playerDtos, room.getHostId().toString())));

        eventPublisher.publishEvent(new PhaseChangedApplicationEvent(
                roomCode, GamePhase.PROMPTING, 1, connected.size(), promptingSeconds, Instant.now().toEpochMilli()));
    }

    @Transactional
    public void submitPrompt(String roomCode, UUID playerId, String text) {
        Room room = getRoom(roomCode);
        if (room.getPhase() != GamePhase.PROMPTING) return;

        Player player = getPlayer(playerId);

        // Idempotency guard: ignore if player already submitted this round
        if (chainEntryRepository.existsByChainOriginPlayerAndRoundAndIsPlaceholderFalse(player, 1)) return;

        Chain chain = chainRepository.findByRoomAndOriginPlayer(room, player).orElseThrow();

        ChainEntry entry = new ChainEntry();
        entry.setChain(chain);
        entry.setRound(1);
        entry.setAuthor(player);
        entry.setText(text);
        entry.setPlaceholder(false);
        chainEntryRepository.save(entry);

        int submitted = countSubmittedForCurrentRound(room);
        int total = playerRepository.findByRoomAndConnectedTrue(room).size();
        eventPublisher.publishEvent(new SubmissionUpdateApplicationEvent(roomCode, submitted, total));

        if (submitted >= total) {
            timerService.cancelTimer(roomCode);
            endPromptingRound(room);
        }
    }

    @Transactional
    public void submitGuess(String roomCode, UUID playerId, String text) {
        Room room = getRoom(roomCode);
        if (room.getPhase() != GamePhase.GUESSING) return;

        Player player = getPlayer(playerId);
        Chain assignedChain = roundAssignmentService.getAssignedChain(room, player, room.getCurrentRound());

        // Idempotency guard
        if (chainEntryRepository.existsByChainAndRoundAndAuthorAndIsPlaceholderFalse(
                assignedChain, room.getCurrentRound(), player)) return;

        ChainEntry entry = new ChainEntry();
        entry.setChain(assignedChain);
        entry.setRound(room.getCurrentRound());
        entry.setAuthor(player);
        entry.setText(text);
        entry.setPlaceholder(false);
        chainEntryRepository.save(entry);

        int submitted = countSubmittedForCurrentRound(room);
        int total = playerRepository.findByRoomAndConnectedTrue(room).size();
        eventPublisher.publishEvent(new SubmissionUpdateApplicationEvent(roomCode, submitted, total));

        if (submitted >= total) {
            timerService.cancelTimer(roomCode);
            endGuessingRound(room);
        }
    }

    @Transactional
    public void onRoundTimerExpired(String roomCode, int round) {
        Room room = getRoom(roomCode);
        if (room.getCurrentRound() != round) return;

        insertPlaceholders(room);
        if (room.getPhase() == GamePhase.PROMPTING) {
            endPromptingRound(room);
        } else {
            endGuessingRound(room);
        }
    }

    @EventListener
    @Transactional
    public void onAllImagesReady(AllImagesReadyApplicationEvent event) {
        onAllImagesReady(event.roomCode());
    }

    @Transactional
    public void onAllImagesReady(String roomCode) {
        Room room = getRoom(roomCode);
        if (room.getPhase() != GamePhase.GENERATING) return;
        int oldRound = room.getCurrentRound();
        int newRound = oldRound + 1;

        room.setCurrentRound(newRound);
        room.setPhase(GamePhase.GUESSING);
        room.setRoundStartedAt(Instant.now());
        roomRepository.save(room);

        List<Player> connected = playerRepository.findByRoomAndConnectedTrue(room);
        Map<UUID, String> playerImageUrls = new HashMap<>();
        for (Player player : connected) {
            Chain assignedChain = roundAssignmentService.getAssignedChain(room, player, newRound);
            chainEntryRepository.findByChainAndRound(assignedChain, oldRound)
                    .ifPresent(entry -> playerImageUrls.put(player.getId(), entry.getImageUrl()));
        }

        Instant now = Instant.now();
        timerService.startRoundTimer(roomCode, newRound, guessingSeconds);
        eventPublisher.publishEvent(new PhaseChangedApplicationEvent(
                roomCode, GamePhase.GUESSING, newRound, room.getTotalRounds(), guessingSeconds, now.toEpochMilli()));
        eventPublisher.publishEvent(new RoundReadyApplicationEvent(roomCode, playerImageUrls));
    }

    @Transactional
    public void advanceShowcase(String roomCode, UUID requestingPlayerId) {
        Room room = getRoom(roomCode);
        if (!room.getHostId().equals(requestingPlayerId)) return;

        int newIndex = showcaseCounters.merge(roomCode, 1, Integer::sum);
        eventPublisher.publishEvent(new ShowcaseApplicationEvent(roomCode, newIndex));
    }

    // ---- Private helpers ----

    private void insertPlaceholders(Room room) {
        List<Player> connected = playerRepository.findByRoomAndConnectedTrue(room);
        for (Player player : connected) {
            Chain chain = resolveChainForPlayer(room, player);
            chainEntryRepository.findByChainAndRound(chain, room.getCurrentRound()).orElseGet(() -> {
                ChainEntry placeholder = new ChainEntry();
                placeholder.setChain(chain);
                placeholder.setRound(room.getCurrentRound());
                placeholder.setAuthor(null);
                placeholder.setText("Wise Hipiotic Cow");
                placeholder.setPlaceholder(true);
                return chainEntryRepository.save(placeholder);
            });
        }
    }

    private Chain resolveChainForPlayer(Room room, Player player) {
        if (room.getPhase() == GamePhase.PROMPTING) {
            return chainRepository.findByRoomAndOriginPlayer(room, player).orElseThrow();
        } else {
            return roundAssignmentService.getAssignedChain(room, player, room.getCurrentRound());
        }
    }

    private void endPromptingRound(Room room) {
        room.setPhase(GamePhase.GENERATING);
        roomRepository.save(room);

        triggerImageGeneration(room);

        eventPublisher.publishEvent(new PhaseChangedApplicationEvent(
                room.getRoomCode(), GamePhase.GENERATING, room.getCurrentRound(),
                room.getTotalRounds(), 0, Instant.now().toEpochMilli()));
    }

    private void endGuessingRound(Room room) {
        if (room.getCurrentRound() == room.getTotalRounds()) {
            room.setPhase(GamePhase.RESULTS);
            roomRepository.save(room);

            eventPublisher.publishEvent(new PhaseChangedApplicationEvent(
                    room.getRoomCode(), GamePhase.RESULTS, room.getCurrentRound(),
                    room.getTotalRounds(), 0, Instant.now().toEpochMilli()));

            List<Chain> chains = chainRepository.findByRoom(room);
            List<ChainDto> chainDtos = chains.stream()
                    .map(chain -> {
                        List<ChainEntryDto> entryDtos = chainEntryRepository.findByChainOrderByRoundAsc(chain).stream()
                                .map(e -> new ChainEntryDto(
                                        e.getAuthor() != null ? e.getAuthor().getId().toString() : null,
                                        e.getAuthor() != null ? e.getAuthor().getAvatarId() : null,
                                        e.getText(),
                                        e.getImageUrl(),
                                        e.isPlaceholder()
                                ))
                                .toList();
                        return new ChainDto(entryDtos);
                    })
                    .toList();

            eventPublisher.publishEvent(new GameResultsApplicationEvent(room.getRoomCode(), new GameResultsEvent(chainDtos)));
        } else {
            room.setPhase(GamePhase.GENERATING);
            roomRepository.save(room);

            triggerImageGeneration(room);

            eventPublisher.publishEvent(new PhaseChangedApplicationEvent(
                    room.getRoomCode(), GamePhase.GENERATING, room.getCurrentRound(),
                    room.getTotalRounds(), 0, Instant.now().toEpochMilli()));
        }
    }

    private void triggerImageGeneration(Room room) {
        String roomCode = room.getRoomCode();
        List<Chain> chains = chainRepository.findByRoom(room);

        List<CompletableFuture<Void>> futures = chains.stream()
                .map(chain -> chainEntryRepository.findByChainAndRound(chain, room.getCurrentRound())
                        .<CompletableFuture<Void>>map(entry -> {
                            if (entry.getText() == null) {
                                return CompletableFuture.completedFuture(null);
                            }
                            return imageGenerationService.generateImage(entry.getText())
                                    .thenAccept(imageUrl -> {
                                        entry.setImageUrl(imageUrl);
                                        chainEntryRepository.save(entry);
                                    })
                                    .exceptionally(ex -> null); // proceed even if one image fails
                        })
                        .orElse(CompletableFuture.completedFuture(null)))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenCompleteAsync((result, ex) ->
                        eventPublisher.publishEvent(new AllImagesReadyApplicationEvent(roomCode)));
    }

    private int countSubmittedForCurrentRound(Room room) {
        List<Chain> chains = chainRepository.findByRoom(room);
        return (int) chainEntryRepository.countByChainInAndRound(chains, room.getCurrentRound());
    }

    private Room getRoom(String roomCode) {
        return roomRepository.findByRoomCode(roomCode)
                .orElseThrow(() -> new GameException("Room not found: " + roomCode));
    }

    private Player getPlayer(UUID playerId) {
        return playerRepository.findById(playerId)
                .orElseThrow(() -> new GameException("Player not found: " + playerId));
    }
}
