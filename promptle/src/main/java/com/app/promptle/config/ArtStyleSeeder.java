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

    private static final List<String> DEFAULTS = List.of(
            "watercolor painting",
            "pixel art",
            "oil painting",
            "comic book art",
            "japanese woodblock print",
            "art nouveau illustration",
            "cyberpunk neon digital art",
            "charcoal sketch",
            "studio ghibli anime",
            "retro poster art",
            "stained glass art",
            "vaporwave aesthetic"
    );

    private final ArtStyleRepository artStyleRepository;

    public ArtStyleSeeder(ArtStyleRepository artStyleRepository) {
        this.artStyleRepository = artStyleRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (artStyleRepository.count() == 0) {
            DEFAULTS.forEach(name -> {
                ArtStyle style = new ArtStyle();
                style.setName(name);
                artStyleRepository.save(style);
            });
        }
    }
}
