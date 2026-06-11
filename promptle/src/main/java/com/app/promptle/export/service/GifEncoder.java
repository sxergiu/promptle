package com.app.promptle.export.service;

import com.app.promptle.export.service.FrameRenderer.FrameSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Assembles the rendered frames into a GIF with smooth crossfades.
 *
 * Each frame is fed as its own looped still image and chained through FFmpeg's
 * {@code xfade} filter (one transition per adjacent pair), then quantized to a
 * GIF palette in a single pass via {@code split}/{@code palettegen}/{@code paletteuse}.
 */
@Service
public class GifEncoder {

    private static final Logger log = LoggerFactory.getLogger(GifEncoder.class);

    private final String ffmpegPath;

    public GifEncoder(@Value("${export.gif.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public byte[] encode(Path tempDir, List<FrameSpec> frames, double transition,
                         int fps, int outputWidth) throws IOException, InterruptedException {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("No frames to encode");
        }
        Path outputPath = tempDir.resolve("output.gif");

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        int n = frames.size();
        // One looped still per frame. A frame that both fades IN and fades OUT must
        // outlast its display time by `transition` on EACH side, so it needs
        // duration + 2*transition; using a single overlap starves the chained xfade
        // and drops late frames. The first/last frames only fade once, but the extra
        // (unused) tail is harmless, so every input gets duration + 2*transition.
        for (int i = 0; i < n; i++) {
            double len = frames.get(i).duration() + 2 * transition;
            cmd.add("-loop");
            cmd.add("1");
            cmd.add("-t");
            cmd.add(fmt(len));
            cmd.add("-i");
            cmd.add(tempDir.resolve(frames.get(i).filename()).toString());
        }
        cmd.add("-filter_complex");
        cmd.add(buildFilter(frames, transition, fps, outputWidth));
        cmd.add("-map");
        cmd.add("[out]");
        cmd.add("-y");
        cmd.add(outputPath.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);
        runProcess(pb, "GIF encoding");

        byte[] gifBytes = Files.readAllBytes(outputPath);
        log.info("GIF encoded successfully: {} frames, {} bytes", n, gifBytes.length);
        return gifBytes;
    }

    /**
     * Builds the filtergraph: normalize each still (consistent fps/size/format/timebase),
     * chain xfade crossfades with cumulative offsets, then GIF-quantize.
     */
    private String buildFilter(List<FrameSpec> frames, double transition, int fps, int outputWidth) {
        int n = frames.size();
        StringBuilder fc = new StringBuilder();

        for (int i = 0; i < n; i++) {
            fc.append('[').append(i).append(":v]")
              .append("format=rgba,")
              .append("scale=").append(outputWidth).append(":-1:flags=lanczos,")
              .append("fps=").append(fps).append(',')
              .append("setsar=1,settb=AVTB")
              .append("[v").append(i).append("];");
        }

        String prev = "v0";
        double offset = 0;
        for (int m = 1; m < n; m++) {
            // offset_m = sum(durations[0..m-1]) + (m-1)*transition
            offset += frames.get(m - 1).duration();
            if (m > 1) {
                offset += transition;
            }
            String out = (m == n - 1) ? "vx" : ("x" + m);
            fc.append('[').append(prev).append("][v").append(m).append(']')
              .append("xfade=transition=fade:duration=").append(fmt(transition))
              .append(":offset=").append(fmt(offset))
              .append('[').append(out).append("];");
            prev = out;
        }
        if (n == 1) {
            prev = "v0";
        }

        fc.append('[').append(prev).append("]split[s0][s1];")
          .append("[s1]palettegen=stats_mode=diff[p];")
          .append("[s0][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle[out]");
        return fc.toString();
    }

    private String fmt(double v) {
        return String.format(Locale.US, "%.3f", v);
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
