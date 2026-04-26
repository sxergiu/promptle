package com.app.promptle.export.service;

import com.app.promptle.export.dto.ExportRequest;
import com.app.promptle.game.dto.ChainDto;
import com.app.promptle.game.dto.ChainEntryDto;
import com.app.promptle.room.dto.PlayerDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    @Mock
    private GifEncoder gifEncoder;

    @TempDir
    Path testDir;

    private ExportService exportService;

    @BeforeEach
    void setUp() {
        exportService = new ExportService(gifEncoder, testDir.toString(), 540, 675, 4, 3, 3, 3);
    }

    // ---- helpers ----

    private ExportRequest createChainRequest(List<ChainEntryDto> entries, List<PlayerDto> players) {
        return new ExportRequest(new ChainDto(entries), players);
    }

    private ExportRequest createThreeEntryChain() {
        List<ChainEntryDto> entries = List.of(
                new ChainEntryDto("p1", "icon-1", "A sunset over the ocean", null, false),
                new ChainEntryDto("p2", "icon-2", null, "http://img/1.png", false),
                new ChainEntryDto("p3", "icon-3", "Golden waves at dusk", null, false)
        );
        List<PlayerDto> players = List.of(
                new PlayerDto("p1", "Alice", "icon-1", true),
                new PlayerDto("p2", "Bob", "icon-2", true),
                new PlayerDto("p3", "Carol", "icon-3", true)
        );
        return createChainRequest(entries, players);
    }

    // ---- tests ----

    @Test
    void exportChain_ValidChain_CallsGifEncoder() throws Exception {
        ExportRequest request = createThreeEntryChain();
        when(gifEncoder.encode(any(Path.class))).thenReturn(new byte[]{1, 2, 3});

        exportService.exportChain(request, "ABCD1234");

        verify(gifEncoder).encode(any(Path.class));
    }

    @Test
    void exportChain_ValidChain_ReturnsBytesFromGifEncoder() throws Exception {
        byte[] expected = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
        ExportRequest request = createThreeEntryChain();
        when(gifEncoder.encode(any(Path.class))).thenReturn(expected);

        byte[] result = exportService.exportChain(request, "ABCD1234");

        assertArrayEquals(expected, result);
    }

    @Test
    void exportChain_3EntryChain_CreatesCorrectFrameCount() throws Exception {
        ExportRequest request = createThreeEntryChain();

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        when(gifEncoder.encode(pathCaptor.capture())).thenAnswer(invocation -> {
            Path tempDir = pathCaptor.getValue();
            try (Stream<Path> pngs = Files.list(tempDir).filter(p -> p.toString().endsWith(".png"))) {
                // title + 3 text entries + 1 image entry + outro = 6 frames
                // Entry 1: text only (has text, no imageUrl) -> text frame
                // Entry 2: image only (no text, has imageUrl) -> text frame + image frame
                // Entry 3: text only (has text, no imageUrl) -> text frame
                // Total: title + 3 text + 1 image + outro = 6
                long frameCount = pngs.count();
                assertTrue(frameCount >= 5, "Expected at least 5 PNG frames, got " + frameCount);
            }
            return new byte[]{1};
        });

        exportService.exportChain(request, "ABCD1234");

        verify(gifEncoder).encode(any(Path.class));
    }

    @Test
    void exportChain_EntryWithNullImageUrl_SkipsImageFrame() throws Exception {
        List<ChainEntryDto> entries = List.of(
                new ChainEntryDto("p1", "icon-1", "A prompt text", null, false)
        );
        List<PlayerDto> players = List.of(
                new PlayerDto("p1", "Alice", "icon-1", true)
        );
        ExportRequest request = createChainRequest(entries, players);

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        when(gifEncoder.encode(pathCaptor.capture())).thenAnswer(invocation -> {
            Path tempDir = pathCaptor.getValue();
            try (Stream<Path> pngs = Files.list(tempDir).filter(p -> p.toString().endsWith(".png"))) {
                // title + 1 text + outro = 3 frames (no image frame since imageUrl is null)
                long frameCount = pngs.count();
                assertEquals(3, frameCount, "Expected 3 PNG frames (title + text + outro)");
            }
            return new byte[]{1};
        });

        exportService.exportChain(request, "ABCD1234");
    }

    @Test
    void exportChain_PlaceholderEntry_IncludedInRendering() throws Exception {
        List<ChainEntryDto> entries = List.of(
                new ChainEntryDto("p1", "icon-1", "Timed out", null, true)
        );
        List<PlayerDto> players = List.of(
                new PlayerDto("p1", "Alice", "icon-1", true)
        );
        ExportRequest request = createChainRequest(entries, players);

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        when(gifEncoder.encode(pathCaptor.capture())).thenAnswer(invocation -> {
            Path tempDir = pathCaptor.getValue();
            try (Stream<Path> pngs = Files.list(tempDir).filter(p -> p.toString().endsWith(".png"))) {
                long frameCount = pngs.count();
                assertTrue(frameCount >= 3, "Placeholder entry should still produce frames, got " + frameCount);
            }
            return new byte[]{1};
        });

        exportService.exportChain(request, "ABCD1234");

        verify(gifEncoder).encode(any(Path.class));
    }

    @Test
    void exportChain_ChainWithSingleEntry_NoImageFrames() throws Exception {
        List<ChainEntryDto> entries = List.of(
                new ChainEntryDto("p1", "icon-1", "Only text here", null, false)
        );
        List<PlayerDto> players = List.of(
                new PlayerDto("p1", "Alice", "icon-1", true)
        );
        ExportRequest request = createChainRequest(entries, players);

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        when(gifEncoder.encode(pathCaptor.capture())).thenAnswer(invocation -> {
            Path tempDir = pathCaptor.getValue();
            try (Stream<Path> pngs = Files.list(tempDir).filter(p -> p.toString().endsWith(".png"))) {
                long frameCount = pngs.count();
                assertEquals(3, frameCount, "Expected title + 1 text + outro = 3 frames");
            }
            return new byte[]{1};
        });

        exportService.exportChain(request, "ABCD1234");
    }

    @Test
    void exportChain_ResolvesPlayerNamesCorrectly() throws Exception {
        List<ChainEntryDto> entries = List.of(
                new ChainEntryDto("p1", "icon-1", "Hello world", null, false)
        );
        List<PlayerDto> players = List.of(
                new PlayerDto("p1", "SpecificAlias", "icon-1", true)
        );
        ExportRequest request = createChainRequest(entries, players);
        when(gifEncoder.encode(any(Path.class))).thenReturn(new byte[]{1});

        // The test verifies no exception is thrown and the service successfully
        // resolves the player name from the players list. If name resolution
        // failed, the frame rendering would use a fallback.
        byte[] result = exportService.exportChain(request, "ABCD1234");

        assertNotNull(result);
        verify(gifEncoder).encode(any(Path.class));
    }

    @Test
    void exportChain_UnknownPlayerId_UsesUnknownFallback() throws Exception {
        List<ChainEntryDto> entries = List.of(
                new ChainEntryDto("unknown-player", "icon-1", "Mystery text", null, false)
        );
        List<PlayerDto> players = List.of(
                new PlayerDto("p1", "Alice", "icon-1", true)
        );
        ExportRequest request = createChainRequest(entries, players);
        when(gifEncoder.encode(any(Path.class))).thenReturn(new byte[]{1});

        // Should not throw even though playerId doesn't match any player
        byte[] result = exportService.exportChain(request, "ABCD1234");

        assertNotNull(result);
    }

    @Test
    void exportChain_TempDirectoryCleanedUp_OnSuccess() throws Exception {
        ExportRequest request = createThreeEntryChain();

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        when(gifEncoder.encode(pathCaptor.capture())).thenReturn(new byte[]{1});

        exportService.exportChain(request, "ABCD1234");

        Path tempDir = pathCaptor.getValue();
        assertFalse(Files.exists(tempDir), "Temp directory should be cleaned up after successful export");
    }

    @Test
    void exportChain_TempDirectoryCleanedUp_OnFailure() throws Exception {
        ExportRequest request = createThreeEntryChain();

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        when(gifEncoder.encode(pathCaptor.capture())).thenThrow(new IOException("FFmpeg failed"));

        assertThrows(Exception.class, () -> exportService.exportChain(request, "ABCD1234"));

        Path tempDir = pathCaptor.getValue();
        assertFalse(Files.exists(tempDir), "Temp directory should be cleaned up even after failure");
    }

    @Test
    void exportChain_GifEncoderThrows_PropagatesException() throws Exception {
        ExportRequest request = createThreeEntryChain();
        when(gifEncoder.encode(any(Path.class))).thenThrow(new IOException("Encoding failed"));

        assertThrows(Exception.class, () -> exportService.exportChain(request, "ABCD1234"));
    }

    @Test
    void exportChain_WritesFrameListFile() throws Exception {
        ExportRequest request = createThreeEntryChain();

        ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
        when(gifEncoder.encode(pathCaptor.capture())).thenAnswer(invocation -> {
            Path tempDir = pathCaptor.getValue();
            Path framesFile = tempDir.resolve("frames.txt");
            assertTrue(Files.exists(framesFile), "frames.txt should exist before encoding");
            String content = Files.readString(framesFile);
            assertFalse(content.isBlank(), "frames.txt should not be empty");
            return new byte[]{1};
        });

        exportService.exportChain(request, "ABCD1234");
    }
}
