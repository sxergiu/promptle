package com.app.promptle.config;

import com.app.promptle.game.model.ArtStyle;
import com.app.promptle.game.repository.ArtStyleRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class ArtStyleSeeder implements ApplicationRunner {

    // Styles are phrased as render *modifiers* ("texture", "accents", "shading")
    // rather than *subjects* ("painting", "art") so they tell the model how to
    // render the player's prompt instead of competing with it as content.
    // See promptle-docs/fine-tune/style-dominance.md.
    private static final List<String> DEFAULTS = List.of(
            "watercolor texture",
            "pixel-art shading",
            "oil-paint texture",
            "comic-book inking",
            "japanese woodblock linework",
            "art nouveau ornamentation",
            "subtle cyberpunk neon accents",
            "charcoal shading",
            "soft studio ghibli anime shading",
            "retro poster coloring",
            "stained-glass color segments",
            "subtle vaporwave color grading"
    );

    private final ArtStyleRepository artStyleRepository;

    public ArtStyleSeeder(ArtStyleRepository artStyleRepository) {
        this.artStyleRepository = artStyleRepository;
    }

    // Reseed from DEFAULTS on every boot so phrasing changes take effect on redeploy.
    // Safe to wipe: chains copy the style into their own String column (no FK to art_styles).
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // deleteAllInBatch issues an immediate bulk DELETE; plain deleteAll() only
        // queues row deletes, which Hibernate flushes *after* the inserts below —
        // so on redeploy the re-inserts hit the unique(name) constraint and the app
        // crashes on boot (502 at the gateway). Bulk-delete first to avoid that.
        artStyleRepository.deleteAllInBatch();
        DEFAULTS.forEach(name -> {
            ArtStyle style = new ArtStyle();
            style.setName(name);
            artStyleRepository.save(style);
        });
    }
}
