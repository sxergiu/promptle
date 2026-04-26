package com.app.promptle.export.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class GifEncoder {

    private static final Logger log = LoggerFactory.getLogger(GifEncoder.class);

    private final String ffmpegPath;

    public GifEncoder(@Value("${export.gif.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public byte[] encode(Path tempDir) throws IOException, InterruptedException {
        Path framesFile = tempDir.resolve("frames.txt");
        Path palettePath = tempDir.resolve("palette.png");
        Path outputPath = tempDir.resolve("output.gif");

        // Pass 1: palette generation
        ProcessBuilder pass1 = new ProcessBuilder(
                ffmpegPath,
                "-f", "concat",
                "-safe", "0",
                "-i", framesFile.toString(),
                "-vf", "fps=10,scale=540:-1:flags=lanczos,palettegen=stats_mode=diff",
                "-y", palettePath.toString()
        );
        pass1.directory(tempDir.toFile());
        pass1.redirectErrorStream(true);
        runProcess(pass1, "palette generation");

        // Pass 2: GIF encoding
        ProcessBuilder pass2 = new ProcessBuilder(
                ffmpegPath,
                "-f", "concat",
                "-safe", "0",
                "-i", framesFile.toString(),
                "-i", palettePath.toString(),
                "-lavfi", "fps=10,scale=540:-1:flags=lanczos [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
                "-y", outputPath.toString()
        );
        pass2.directory(tempDir.toFile());
        pass2.redirectErrorStream(true);
        runProcess(pass2, "GIF encoding");

        byte[] gifBytes = Files.readAllBytes(outputPath);

        // Clean up palette
        Files.deleteIfExists(palettePath);

        log.info("GIF encoded successfully: {} bytes", gifBytes.length);
        return gifBytes;
    }

    private void runProcess(ProcessBuilder pb, String passName) throws IOException, InterruptedException {
        Process process = pb.start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("FFmpeg " + passName + " timed out after 5 minutes");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("FFmpeg {} failed with exit code {}: {}", passName, exitCode, output);
            throw new RuntimeException("FFmpeg " + passName + " failed (exit code " + exitCode + "): " + output);
        }
        log.debug("FFmpeg {} completed successfully", passName);
    }
}
