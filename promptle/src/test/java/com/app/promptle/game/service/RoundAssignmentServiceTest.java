package com.app.promptle.game.service;

import com.app.promptle.common.exception.GameException;
import com.app.promptle.game.model.Chain;
import com.app.promptle.game.model.GamePhase;
import com.app.promptle.game.model.RoundAssignment;
import com.app.promptle.game.repository.RoundAssignmentRepository;
import com.app.promptle.room.model.Player;
import com.app.promptle.room.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoundAssignmentServiceTest {

    @Mock
    private RoundAssignmentRepository roundAssignmentRepository;

    private RoundAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new RoundAssignmentService(roundAssignmentRepository);
    }

    // ---- generateAssignments ----

    @Test
    void generateAssignments_TotalRowCount_IsNTimesNMinus1() {
        int n = 4;
        Room room = buildRoom(n);
        List<Player> players = buildPlayers(n, room);
        List<Chain> chains = buildChains(n, players, room);

        when(roundAssignmentRepository.save(any(RoundAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.generateAssignments(room, players, chains);

        verify(roundAssignmentRepository, times(n * (n - 1))).save(any(RoundAssignment.class));
    }

    @Test
    void generateAssignments_Round2_CyclicFormula_N4() {
        int n = 4;
        Room room = buildRoom(n);
        List<Player> players = buildPlayers(n, room);
        List<Chain> chains = buildChains(n, players, room);

        List<RoundAssignment> saved = new ArrayList<>();
        when(roundAssignmentRepository.save(any(RoundAssignment.class)))
                .thenAnswer(inv -> {
                    RoundAssignment ra = inv.getArgument(0);
                    saved.add(ra);
                    return ra;
                });

        service.generateAssignments(room, players, chains);

        List<RoundAssignment> round2 = saved.stream()
                .filter(ra -> ra.getRound() == 2)
                .collect(Collectors.toList());

        assertEquals(4, round2.size());
        for (int i = 0; i < n; i++) {
            int finalI = i;
            Player player = players.get(i);
            int expectedChainIndex = (i + 1) % n;
            Chain expectedChain = chains.get(expectedChainIndex);
            boolean found = round2.stream()
                    .anyMatch(ra -> ra.getPlayer().getId().equals(player.getId())
                            && ra.getChain().getId().equals(expectedChain.getId()));
            assertTrue(found, "Round 2: player " + finalI + " should be assigned chain " + expectedChainIndex);
        }
    }

    @Test
    void generateAssignments_Round3_CyclicFormula_N4() {
        int n = 4;
        Room room = buildRoom(n);
        List<Player> players = buildPlayers(n, room);
        List<Chain> chains = buildChains(n, players, room);

        List<RoundAssignment> saved = new ArrayList<>();
        when(roundAssignmentRepository.save(any(RoundAssignment.class)))
                .thenAnswer(inv -> {
                    RoundAssignment ra = inv.getArgument(0);
                    saved.add(ra);
                    return ra;
                });

        service.generateAssignments(room, players, chains);

        List<RoundAssignment> round3 = saved.stream()
                .filter(ra -> ra.getRound() == 3)
                .collect(Collectors.toList());

        for (int i = 0; i < n; i++) {
            Player player = players.get(i);
            int expectedChainIndex = (i + 2) % n;
            Chain expectedChain = chains.get(expectedChainIndex);
            boolean found = round3.stream()
                    .anyMatch(ra -> ra.getPlayer().getId().equals(player.getId())
                            && ra.getChain().getId().equals(expectedChain.getId()));
            assertTrue(found, "Round 3: player " + i + " should be assigned chain " + expectedChainIndex);
        }
    }

    @Test
    void generateAssignments_Round4_CyclicFormula_N4() {
        int n = 4;
        Room room = buildRoom(n);
        List<Player> players = buildPlayers(n, room);
        List<Chain> chains = buildChains(n, players, room);

        List<RoundAssignment> saved = new ArrayList<>();
        when(roundAssignmentRepository.save(any(RoundAssignment.class)))
                .thenAnswer(inv -> {
                    RoundAssignment ra = inv.getArgument(0);
                    saved.add(ra);
                    return ra;
                });

        service.generateAssignments(room, players, chains);

        List<RoundAssignment> round4 = saved.stream()
                .filter(ra -> ra.getRound() == 4)
                .collect(Collectors.toList());

        for (int i = 0; i < n; i++) {
            Player player = players.get(i);
            int expectedChainIndex = (i + 3) % n;
            Chain expectedChain = chains.get(expectedChainIndex);
            boolean found = round4.stream()
                    .anyMatch(ra -> ra.getPlayer().getId().equals(player.getId())
                            && ra.getChain().getId().equals(expectedChain.getId()));
            assertTrue(found, "Round 4: player " + i + " should be assigned chain " + expectedChainIndex);
        }
    }

    @Test
    void generateAssignments_NoSelfAssignment_ForAllNFrom2To8() {
        for (int n = 2; n <= 8; n++) {
            Room room = buildRoom(n);
            List<Player> players = buildPlayers(n, room);
            List<Chain> chains = buildChains(n, players, room);

            List<RoundAssignment> saved = new ArrayList<>();
            when(roundAssignmentRepository.save(any(RoundAssignment.class)))
                    .thenAnswer(inv -> {
                        RoundAssignment ra = inv.getArgument(0);
                        saved.add(ra);
                        return ra;
                    });

            service.generateAssignments(room, players, chains);

            for (RoundAssignment ra : saved) {
                assertNotEquals(
                        ra.getChain().getOriginPlayer().getId(),
                        ra.getPlayer().getId(),
                        "N=" + n + ": player assigned own chain in round " + ra.getRound()
                );
            }

            reset(roundAssignmentRepository);
        }
    }

    @Test
    void generateAssignments_LatinSquareCompleteness_EachPlayerSeesEachOtherChainExactlyOnce() {
        int n = 5;
        Room room = buildRoom(n);
        List<Player> players = buildPlayers(n, room);
        List<Chain> chains = buildChains(n, players, room);

        List<RoundAssignment> saved = new ArrayList<>();
        when(roundAssignmentRepository.save(any(RoundAssignment.class)))
                .thenAnswer(inv -> {
                    RoundAssignment ra = inv.getArgument(0);
                    saved.add(ra);
                    return ra;
                });

        service.generateAssignments(room, players, chains);

        for (Player player : players) {
            Set<UUID> assignedChainIds = saved.stream()
                    .filter(ra -> ra.getPlayer().getId().equals(player.getId()))
                    .map(ra -> ra.getChain().getId())
                    .collect(Collectors.toSet());

            // Should see exactly n-1 distinct chains (all except own)
            assertEquals(n - 1, assignedChainIds.size(),
                    "Player should see " + (n - 1) + " distinct chains");

            // Should not see own chain
            Chain ownChain = chains.stream()
                    .filter(c -> c.getOriginPlayer().getId().equals(player.getId()))
                    .findFirst().orElseThrow();
            assertFalse(assignedChainIds.contains(ownChain.getId()),
                    "Player should not see own chain");
        }
    }

    @Test
    void generateAssignments_NoCollisionsWithinRound_ForN4() {
        int n = 4;
        Room room = buildRoom(n);
        List<Player> players = buildPlayers(n, room);
        List<Chain> chains = buildChains(n, players, room);

        List<RoundAssignment> saved = new ArrayList<>();
        when(roundAssignmentRepository.save(any(RoundAssignment.class)))
                .thenAnswer(inv -> {
                    RoundAssignment ra = inv.getArgument(0);
                    saved.add(ra);
                    return ra;
                });

        service.generateAssignments(room, players, chains);

        // For each round, chain IDs must be distinct
        Map<Integer, List<RoundAssignment>> byRound = saved.stream()
                .collect(Collectors.groupingBy(RoundAssignment::getRound));

        for (Map.Entry<Integer, List<RoundAssignment>> entry : byRound.entrySet()) {
            List<UUID> chainIds = entry.getValue().stream()
                    .map(ra -> ra.getChain().getId())
                    .collect(Collectors.toList());
            Set<UUID> distinct = new HashSet<>(chainIds);
            assertEquals(distinct.size(), chainIds.size(),
                    "Collision detected in round " + entry.getKey());
        }
    }

    @Test
    void generateAssignments_MinimumN2_CorrectAssignments() {
        int n = 2;
        Room room = buildRoom(n);
        List<Player> players = buildPlayers(n, room);
        List<Chain> chains = buildChains(n, players, room);

        List<RoundAssignment> saved = new ArrayList<>();
        when(roundAssignmentRepository.save(any(RoundAssignment.class)))
                .thenAnswer(inv -> {
                    RoundAssignment ra = inv.getArgument(0);
                    saved.add(ra);
                    return ra;
                });

        service.generateAssignments(room, players, chains);

        assertEquals(2, saved.size(), "N=2 should produce exactly 2 assignments");

        // player 0 → chain 1, player 1 → chain 0
        boolean p0GetsChain1 = saved.stream().anyMatch(ra ->
                ra.getPlayer().getId().equals(players.get(0).getId())
                        && ra.getChain().getId().equals(chains.get(1).getId()));
        boolean p1GetsChain0 = saved.stream().anyMatch(ra ->
                ra.getPlayer().getId().equals(players.get(1).getId())
                        && ra.getChain().getId().equals(chains.get(0).getId()));

        assertTrue(p0GetsChain1, "Player 0 should be assigned chain 1");
        assertTrue(p1GetsChain0, "Player 1 should be assigned chain 0");
    }

    @Test
    void generateAssignments_MaximumN8_56Assignments_AllConstraintsSatisfied() {
        int n = 8;
        Room room = buildRoom(n);
        List<Player> players = buildPlayers(n, room);
        List<Chain> chains = buildChains(n, players, room);

        List<RoundAssignment> saved = new ArrayList<>();
        when(roundAssignmentRepository.save(any(RoundAssignment.class)))
                .thenAnswer(inv -> {
                    RoundAssignment ra = inv.getArgument(0);
                    saved.add(ra);
                    return ra;
                });

        service.generateAssignments(room, players, chains);

        assertEquals(56, saved.size(), "N=8 should produce 56 assignments (8 * 7)");

        // No self-assignment
        for (RoundAssignment ra : saved) {
            assertNotEquals(ra.getChain().getOriginPlayer().getId(), ra.getPlayer().getId());
        }

        // No collision within any round
        Map<Integer, List<RoundAssignment>> byRound = saved.stream()
                .collect(Collectors.groupingBy(RoundAssignment::getRound));
        for (Map.Entry<Integer, List<RoundAssignment>> entry : byRound.entrySet()) {
            Set<UUID> chainIds = entry.getValue().stream()
                    .map(ra -> ra.getChain().getId())
                    .collect(Collectors.toSet());
            assertEquals(n, chainIds.size(), "Should be no chain collisions within round " + entry.getKey());
        }
    }

    @Test
    void generateAssignments_PersistsAllAssignmentsUpfront_ImmediatelyAfterCall() {
        int n = 3;
        Room room = buildRoom(n);
        List<Player> players = buildPlayers(n, room);
        List<Chain> chains = buildChains(n, players, room);

        when(roundAssignmentRepository.save(any(RoundAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.generateAssignments(room, players, chains);

        // All n*(n-1) rows saved immediately, not lazily
        verify(roundAssignmentRepository, times(n * (n - 1))).save(any(RoundAssignment.class));
    }

    // ---- getAssignedChain ----

    @Test
    void getAssignedChain_ReturnsChain_WhenAssignmentExists() {
        Room room = buildRoom(2);
        Player player = buildPlayers(2, room).get(0);
        Chain chain = new Chain();
        chain.setId(UUID.randomUUID());

        RoundAssignment assignment = new RoundAssignment();
        assignment.setChain(chain);

        when(roundAssignmentRepository.findByRoomAndRoundAndPlayer(room, 2, player))
                .thenReturn(Optional.of(assignment));

        Chain result = service.getAssignedChain(room, player, 2);

        assertEquals(chain.getId(), result.getId());
    }

    @Test
    void getAssignedChain_ThrowsGameException_WhenNoAssignmentFound() {
        Room room = buildRoom(2);
        Player player = buildPlayers(2, room).get(0);

        when(roundAssignmentRepository.findByRoomAndRoundAndPlayer(room, 2, player))
                .thenReturn(Optional.empty());

        assertThrows(GameException.class, () -> service.getAssignedChain(room, player, 2));
    }

    // ---- Helpers ----

    private Room buildRoom(int n) {
        Room room = new Room();
        room.setId(UUID.randomUUID());
        room.setRoomCode("TESTROOM");
        room.setPhase(GamePhase.PROMPTING);
        room.setTotalRounds(n);
        room.setCurrentRound(1);
        room.setHostId(UUID.randomUUID());
        return room;
    }

    private List<Player> buildPlayers(int n, Room room) {
        return IntStream.range(0, n).mapToObj(i -> {
            Player p = new Player();
            p.setId(UUID.randomUUID());
            p.setAlias("Player" + i);
            p.setAvatarId("icon-" + i);
            p.setRoom(room);
            p.setConnected(true);
            p.setJoinedAt(Instant.now().plusSeconds(i));
            return p;
        }).collect(Collectors.toList());
    }

    private List<Chain> buildChains(int n, List<Player> players, Room room) {
        return IntStream.range(0, n).mapToObj(i -> {
            Chain chain = new Chain();
            chain.setId(UUID.randomUUID());
            chain.setRoom(room);
            chain.setOriginPlayer(players.get(i));
            return chain;
        }).collect(Collectors.toList());
    }
}
