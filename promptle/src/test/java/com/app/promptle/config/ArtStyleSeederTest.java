package com.app.promptle.config;

import com.app.promptle.game.model.ArtStyle;
import com.app.promptle.game.repository.ArtStyleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
    void run_InsertsDefaultStyles_WhenTableIsEmpty() throws Exception {
        when(artStyleRepository.count()).thenReturn(0L);

        artStyleSeeder.run(applicationArguments);

        ArgumentCaptor<ArtStyle> captor = ArgumentCaptor.forClass(ArtStyle.class);
        verify(artStyleRepository, times(12)).save(captor.capture());
        List<ArtStyle> saved = captor.getAllValues();
        assertEquals(12, saved.size());
        assertEquals(12, saved.stream().map(ArtStyle::getName).distinct().count(),
                "All seeded style names must be unique");
    }

    @Test
    void run_DoesNotInsert_WhenTableAlreadyHasStyles() throws Exception {
        when(artStyleRepository.count()).thenReturn(5L);

        artStyleSeeder.run(applicationArguments);

        verify(artStyleRepository, never()).save(any());
    }
}
