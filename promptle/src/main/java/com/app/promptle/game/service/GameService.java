package com.app.promptle.game.service;

import com.app.promptle.common.exception.GameException;
import com.app.promptle.game.dto.*;
import com.app.promptle.game.event.*;
import com.app.promptle.game.model.*;
import com.app.promptle.game.repository.*;
import com.app.promptle.image.api.ImageGenerationService;
import com.app.promptle.image.api.ImageStorageService;
import com.app.promptle.image.filter.PromptFilter;
import com.app.promptle.room.dto.PlayerDto;
import com.app.promptle.room.dto.RoomEvent;
import com.app.promptle.room.dto.RoomEventType;
import com.app.promptle.room.event.RoomApplicationEvent;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import com.app.promptle.room.repository.PlayerRepository;
import com.app.promptle.room.repository.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final RoomRepository roomRepository;
    private final PlayerRepository playerRepository;
    private final ChainRepository chainRepository;
    private final ChainEntryRepository chainEntryRepository;
    private final ArtStyleRepository artStyleRepository;
    private final RoundAssignmentService roundAssignmentService;
    private final TimerService timerService;
    private final ImageGenerationService imageGenerationService;
    private final ImageStorageService imageStorageService;
    private final PromptFilter promptFilter;
    private final ApplicationEventPublisher eventPublisher;
    private final long promptingSeconds;
    private final long guessingSeconds;
    // Attention weight applied to the art-style text so it shapes the look without
    // crowding out the player's actual prompt/guess. See promptle-docs/fine-tune.
    private final double styleWeightFirstRound;
    private final double styleWeightLaterRounds;
    // Two equal generation modes for rounds 2+ (round 1 is always text-to-image):
    //   image-to-image — seed each image from the previous one (visual lineage, but
    //     leaks details the guesser never described; subtractive edits don't take).
    //   text-to-image  — generate each round fresh from the guess text (faithful to
    //     every prompt change incl. removals; no visual carry-over).
    // See promptle-docs/fine-tune/cfg-denoise-style-discussion.md.
    private final boolean imageToImageMode;

    // In-memory showcase counters per room (reset in startGame so repeat games in the same room start clean)
    private final Map<String, Integer> showcaseCounters = new ConcurrentHashMap<>();

    public GameService(RoomRepository roomRepository,
                       PlayerRepository playerRepository,
                       ChainRepository chainRepository,
                       ChainEntryRepository chainEntryRepository,
                       ArtStyleRepository artStyleRepository,
                       RoundAssignmentService roundAssignmentService,
                       TimerService timerService,
                       ImageGenerationService imageGenerationService,
                       ImageStorageService imageStorageService,
                       PromptFilter promptFilter,
                       ApplicationEventPublisher eventPublisher,
                       @Value("${game.timer.prompting-seconds:60}") long promptingSeconds,
                       @Value("${game.timer.guessing-seconds:60}") long guessingSeconds,
                       @Value("${image.style.weight.first-round:0.45}") double styleWeightFirstRound,
                       @Value("${image.style.weight.later-rounds:0.45}") double styleWeightLaterRounds,
                       @Value("${image.generation.chain-mode:image-to-image}") String chainMode) {
        this.roomRepository = roomRepository;
        this.playerRepository = playerRepository;
        this.chainRepository = chainRepository;
        this.chainEntryRepository = chainEntryRepository;
        this.artStyleRepository = artStyleRepository;
        this.roundAssignmentService = roundAssignmentService;
        this.timerService = timerService;
        this.imageGenerationService = imageGenerationService;
        this.imageStorageService = imageStorageService;
        this.promptFilter = promptFilter;
        this.eventPublisher = eventPublisher;
        this.promptingSeconds = promptingSeconds;
        this.guessingSeconds = guessingSeconds;
        this.styleWeightFirstRound = styleWeightFirstRound;
        this.styleWeightLaterRounds = styleWeightLaterRounds;
        this.imageToImageMode = "image-to-image".equalsIgnoreCase(chainMode);
        log.info("Image generation chain mode: {}", imageToImageMode ? "image-to-image" : "text-to-image");
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

        // Reset showcase counter so repeated games in the same room start fresh
        showcaseCounters.remove(roomCode);

        List<Player> connected = playerRepository.findByRoomAndConnectedTrue(room);

        if (connected.size() < 1 || connected.size() > 8) {
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

        List<ArtStyle> allStyles = artStyleRepository.findAll();
        if (allStyles.size() < orderedPlayers.size()) {
            throw new GameException("Not enough art styles configured for " + orderedPlayers.size() + " players");
        }
        List<ArtStyle> shuffledStyles = new ArrayList<>(allStyles);
        Collections.shuffle(shuffledStyles);

        List<Chain> savedChains = new ArrayList<>();
        for (int i = 0; i < orderedPlayers.size(); i++) {
            Chain chain = new Chain();
            chain.setRoom(room);
            chain.setOriginPlayer(orderedPlayers.get(i));
            chain.setStyle(shuffledStyles.get(i).getName());
            savedChains.add(chainRepository.save(chain));
        }

        // Round assignments use a cyclic Latin square that requires N >= 2 (no player
        // can guess their own chain). A solo game has a single chain and skips GUESSING,
        // so assignments are neither possible nor needed.
        if (orderedPlayers.size() > 1) {
            roundAssignmentService.generateAssignments(room, orderedPlayers, savedChains);
        }
        timerService.startRoundTimer(roomCode, 1, promptingSeconds);

        List<PlayerDto> playerDtos = connected.stream()
                .map(p -> new PlayerDto(p.getId().toString(), p.getAlias(), p.getAvatarId(), p.isConnected(), false))
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
        entry.setText(promptFilter.sanitize(text));
        entry.setPlaceholder(false);
        chainEntryRepository.save(entry);

        int submitted = countSubmittedForCurrentRound(room);
        int total = room.getTotalRounds();
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
        entry.setText(promptFilter.sanitize(text));
        entry.setPlaceholder(false);
        chainEntryRepository.save(entry);

        int submitted = countSubmittedForCurrentRound(room);
        int total = room.getTotalRounds();
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
        // Guard: if all players submitted before the timer fired, the phase
        // has already advanced (e.g., to GENERATING). Only act if still in
        // a submission-accepting phase to prevent duplicate transitions.
        if (room.getPhase() != GamePhase.PROMPTING && room.getPhase() != GamePhase.GUESSING) return;

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
        log.info("[{}] onAllImagesReady fired", roomCode);
        timerService.cancelGeneratingTimeout(roomCode);
        Room room = getRoom(roomCode);
        if (room.getPhase() != GamePhase.GENERATING) {
            log.warn("[{}] onAllImagesReady: phase is {} — skipping GUESSING transition", roomCode, room.getPhase());
            return;
        }

        // Final round generated: every chain now ends on an image, so reveal the results.
        // Covers both the solo game (1 == 1, guessing skipped entirely) and the final
        // img2img pass after the last guessing round (N == N).
        if (room.getCurrentRound() == room.getTotalRounds()) {
            log.info("[{}] onAllImagesReady: final round generated — going to RESULTS", roomCode);
            transitionToResults(room);
            return;
        }

        int oldRound = room.getCurrentRound();
        int newRound = oldRound + 1;

        room.setCurrentRound(newRound);
        room.setPhase(GamePhase.GUESSING);
        room.setRoundStartedAt(Instant.now());
        roomRepository.save(room);

        List<Player> allPlayers = playerRepository.findByRoom(room);
        Map<UUID, String> playerImageUrls = new HashMap<>();
        for (Player player : allPlayers) {
            Chain assignedChain = roundAssignmentService.getAssignedChain(room, player, newRound);
            chainEntryRepository.findByChainAndRound(assignedChain, oldRound)
                    .ifPresent(entry -> playerImageUrls.put(player.getId(), entry.getImageUrl()));
        }

        Instant now = Instant.now();
        timerService.startRoundTimer(roomCode, newRound, guessingSeconds);
        eventPublisher.publishEvent(new PhaseChangedApplicationEvent(
                roomCode, GamePhase.GUESSING, newRound, room.getTotalRounds(), guessingSeconds, now.toEpochMilli()));
        eventPublisher.publishEvent(new RoundReadyApplicationEvent(roomCode, newRound, playerImageUrls));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStartImageGeneration(StartImageGenerationEvent event) {
        log.info("[{}] onStartImageGeneration fired — starting image generation", event.roomCode());
        Room room = roomRepository.findByRoomCode(event.roomCode()).orElse(null);
        if (room == null || room.getPhase() != GamePhase.GENERATING) {
            log.warn("[{}] onStartImageGeneration: room phase is {} — skipping", event.roomCode(),
                    room == null ? "null" : room.getPhase());
            return;
        }
        triggerImageGeneration(room);
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
        List<Chain> chains = chainRepository.findByRoom(room);
        int round = room.getCurrentRound();
        for (Chain chain : chains) {
            chainEntryRepository.findByChainAndRound(chain, round).orElseGet(() -> {
                // For PROMPTING (round 1), the author is the chain originator.
                // For GUESSING (round 2+), the author is the player assigned to this chain.
                Player author = (room.getPhase() == GamePhase.PROMPTING)
                        ? chain.getOriginPlayer()
                        : roundAssignmentService.getAssignedPlayer(room, chain, round);
                ChainEntry placeholder = new ChainEntry();
                placeholder.setChain(chain);
                placeholder.setRound(round);
                placeholder.setAuthor(author);
                placeholder.setText("Wise Hipiotic Cow");
                placeholder.setPlaceholder(true);
                return chainEntryRepository.save(placeholder);
            });
        }
    }

    private void endPromptingRound(Room room) {
        transitionToGenerating(room);
    }

    /**
     * Always followed by a GENERATING pass — even after the final guessing round, so
     * every chain ends on an image. onAllImagesReady then decides between the next
     * GUESSING round and RESULTS based on currentRound vs totalRounds.
     */
    private void endGuessingRound(Room room) {
        transitionToGenerating(room);
    }

    private void transitionToGenerating(Room room) {
        room.setPhase(GamePhase.GENERATING);
        roomRepository.save(room);

        eventPublisher.publishEvent(new PhaseChangedApplicationEvent(
                room.getRoomCode(), GamePhase.GENERATING, room.getCurrentRound(),
                room.getTotalRounds(), 0, Instant.now().toEpochMilli()));
        // Published AFTER the phase change event — Spring processes after-commit
        // synchronizations in publication order, so the GENERATING broadcast is
        // guaranteed to reach clients before image generation begins.
        eventPublisher.publishEvent(new StartImageGenerationEvent(room.getRoomCode()));

        timerService.startGeneratingTimeout(room.getRoomCode(), room.getCurrentRound());
    }

    /**
     * Moves the room into RESULTS and publishes the final chain data. Reached from
     * onAllImagesReady once the final round's images are generated — the last
     * guessing round in multiplayer, or the single prompt round in a solo game.
     */
    private void transitionToResults(Room room) {
        room.setPhase(GamePhase.RESULTS);
        roomRepository.save(room);

        eventPublisher.publishEvent(new PhaseChangedApplicationEvent(
                room.getRoomCode(), GamePhase.RESULTS, room.getCurrentRound(),
                room.getTotalRounds(), 0, Instant.now().toEpochMilli()));

        List<Chain> chains = chainRepository.findByRoom(room);
        List<ChainDto> chainDtos = chains.stream()
                .map(chain -> {
                    // TODO(chain-style): pass chain.getStyle() into ChainDto constructor once frontend supports it.
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
                            String style = chain.getStyle();
                            double styleWeight = room.getCurrentRound() == 1
                                    ? styleWeightFirstRound
                                    : styleWeightLaterRounds;
                            String decorated = entry.getText()
                                    + (style != null && !style.isBlank()
                                        ? ", (" + style + " style:" + styleWeight + ")"
                                        : "");

                            byte[] previousImageBytes = null;
                            if (imageToImageMode && room.getCurrentRound() > 1) {
                                ChainEntry prevEntry = chainEntryRepository
                                        .findByChainAndRound(chain, room.getCurrentRound() - 1)
                                        .orElse(null);
                                if (prevEntry != null && prevEntry.getImageUrl() != null) {
                                    try {
                                        previousImageBytes = imageStorageService
                                                .fetchImageBytes(prevEntry.getImageUrl());
                                    } catch (Exception e) {
                                        log.warn("Failed to fetch previous image for img2img, falling back to txt2img: {}", e.getMessage());
                                    }
                                }
                            }

                            CompletableFuture<String> future = (previousImageBytes != null)
                                    ? imageGenerationService.generateImageFromImage(decorated, previousImageBytes)
                                    : imageGenerationService.generateImage(decorated);
                            return future
                                    .thenAccept(imageUrl -> {
                                        entry.setImageUrl(imageUrl);
                                        chainEntryRepository.save(entry);
                                    })
                                    .exceptionally(ex -> null); // proceed even if one image fails
                        })
                        .orElse(CompletableFuture.completedFuture(null)))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((result, ex) ->
                        eventPublisher.publishEvent(new AllImagesReadyApplicationEvent(roomCode)));
    }

    private int countSubmittedForCurrentRound(Room room) {
        List<Chain> chains = chainRepository.findByRoom(room);
        return (int) chainEntryRepository.countByChainInAndRoundAndIsPlaceholderFalse(chains, room.getCurrentRound());
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
