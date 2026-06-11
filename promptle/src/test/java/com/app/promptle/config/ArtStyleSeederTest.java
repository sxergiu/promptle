package com.app.promptle.config;

import com.app.promptle.game.model.ArtStyle;
import com.app.promptle.game.repository.ArtStyleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArtStyleSeederTest {

    @Mock private ArtStyleRepository artStyleRepository;
    @Mock private ApplicationArguments applicationArguments;
    @InjectMocks private ArtStyleSeeder artStyleSeeder;

    @Test
    void run_InsertsDefaultStyles() throws Exception {
        artStyleSeeder.run(applicationArguments);

        ArgumentCaptor<ArtStyle> captor = ArgumentCaptor.forClass(ArtStyle.class);
        verify(artStyleRepository, times(12)).save(captor.capture());
        List<ArtStyle> saved = captor.getAllValues();
        assertEquals(12, saved.size());
        assertEquals(12, saved.stream().map(ArtStyle::getName).distinct().count(),
                "All seeded style names must be unique");
    }

    @Test
    void run_WipesExistingStylesBeforeReseeding() throws Exception {
        artStyleSeeder.run(applicationArguments);

        // Reseeds fresh on every boot so phrasing changes take effect on redeploy:
        // the wipe (deleteAllInBatch, to avoid a unique-constraint clash) must happen
        // before the inserts.
        InOrder inOrder = inOrder(artStyleRepository);
        inOrder.verify(artStyleRepository).deleteAllInBatch();
        inOrder.verify(artStyleRepository, times(12)).save(any());
    }
}
