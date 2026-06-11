package com.app.promptle.export.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Dev tool — renders a sample chain straight to a GIF (no game flow / DB / server),
 * so you can eyeball export styling, avatars, transitions, and pacing quickly.
 *
 *   mvn test -Dtest=ExportPreviewTest            # (from promptle/, needs ffmpeg + the export assets)
 *
 * Output: promptle/target/export-preview/output.gif (+ the source frame PNGs).
 * Tagged "preview" so it's easy to exclude from CI if desired (-DexcludedGroups=preview).
 * Tweak the chain below to test different text/avatars/lengths.
 */
@Tag("preview")
class ExportPreviewTest {

    @Test
    void generatePreviewGif() throws Exception {
        Path out = Paths.get("target/export-preview").toAbsolutePath();
        Files.createDirectories(out);

        // (width, height, textDur, imageDur, titleDur, outroDur) — match application.properties.
        FrameRenderer r = new FrameRenderer(1080, 1350, 2.2, 2.2, 1.8, 2.0);

        r.renderTitleFrame(out, "Alice", "PROMPTLE");
        r.renderTextFrame(out, "A wizard riding a giant cat through a neon city at night", "Alice", "icon-7", false, 1, 4, "Prompted");
        r.renderImageFrame(out, syntheticImage(0), 1, 4);
        r.renderTextFrame(out, "Cat-mounted sorcerer cruising downtown", "Bob", "icon-9", false, 2, 4, "Guessed");
        r.renderImageFrame(out, syntheticImage(1), 2, 4);
        r.renderTextFrame(out, "…", "Carol", "icon-13", true, 3, 4, "Guessed");
        r.renderTextFrame(out, "Some cool space pirate vibes with a lot of detail and a much longer guess to test how the bubble wraps over several lines gracefully", "Dave", "icon-17", false, 4, 4, "Guessed");
        r.renderImageFrame(out, syntheticImage(2), 4, 4);
        r.renderOutroFrame(out, "PROMPTLE");

        // (frames, transitionSeconds, fps, outputWidth)
        GifEncoder encoder = new GifEncoder("ffmpeg");
        byte[] gif = encoder.encode(out, r.getFrames(), 0.45, 20, 540);
        Files.write(out.resolve("output.gif"), gif);

        System.out.println("PREVIEW GIF: " + out.resolve("output.gif") + " (" + gif.length + " bytes)");
    }

    /** Stand-in for an AI image: a gradient with a "sun" and "buildings". */
    private byte[] syntheticImage(int variant) throws IOException {
        int w = 768, h = 768;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color a = new Color[]{new Color(0x2b1055), new Color(0x7597de), new Color(0x402218)}[variant % 3];
        Color b = new Color[]{new Color(0x7597de), new Color(0xffd86b), new Color(0xff7e5f)}[variant % 3];
        g.setPaint(new GradientPaint(0, 0, a, w, h, b));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 180));
        g.fillOval(w / 2 - 120, h / 3 - 120, 240, 240);
        g.setColor(new Color(0, 0, 0, 120));
        for (int i = 0; i < 6; i++) {
            g.fillRect(60 + i * 110, h - 260 + (i % 2) * 40, 80, 200);
        }
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
