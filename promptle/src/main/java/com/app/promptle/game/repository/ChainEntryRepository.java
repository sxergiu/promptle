package com.app.promptle.game.repository;

import com.app.promptle.game.model.Chain;
import com.app.promptle.game.model.ChainEntry;
import com.app.promptle.room.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChainEntryRepository extends JpaRepository<ChainEntry, UUID> {

    List<ChainEntry> findByChainOrderByRoundAsc(Chain chain);

    Optional<ChainEntry> findByChainAndRound(Chain chain, int round);

    long countByChainInAndRound(Collection<Chain> chains, int round);

    boolean existsByChainAndRoundAndAuthorAndIsPlaceholderFalse(Chain chain, int round, Player author);

    boolean existsByChainOriginPlayerAndRoundAndIsPlaceholderFalse(Player originPlayer, int round);

    void deleteAllByChainIn(Collection<Chain> chains);

    long countByChainInAndRoundAndIsPlaceholderFalse(Collection<Chain> chains, int round);
}
