package com.app.promptle.export.service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the individual frames that make up the exported chain GIF, matching
 * the app's dark-theme visual language: characters sit in an avatar-bg circle
 * with a green identity ring, guesses appear in an elevated surface card, and
 * the generated image gets a green-bordered sticker with a soft offset shadow.
 *
 * Designed for a 2x portrait canvas (default 1080x1350) so text and edges stay
 * crisp after FFmpeg downscales the GIF. Avatars are loaded on demand by their
 * runtime id (icon-N.png) — see {@code scripts/gen-export-assets.sh}.
 */
public class FrameRenderer {

    // Palette mirrors styles.scss dark theme.
    private static final Color BG = new Color(0x16, 0x16, 0x1a);
    private static final Color SURFACE = new Color(0x26, 0x26, 0x2c);
    private static final Color AVATAR_BG = new Color(0x4e, 0x4e, 0x54);
    private static final Color ACCENT = new Color(0x6f, 0x9d, 0x78);   // brightened p-green for pop on dark
    private static final Color HAIRLINE = new Color(255, 255, 255, 22);
    private static final Color TEXT_PRIMARY = new Color(0xf2, 0xf2, 0xf4);
    private static final Color TEXT_MUTED = new Color(255, 255, 255, 150);
    private static final Color SHADOW = new Color(0, 0, 0, 90);

    // Layout (tuned for the default 1080x1350 canvas; positions derive from width/height).
    private static final int MARGIN = 80;
    private static final int TOP = 96;
    private static final int AVATAR = 116;

    private final int width;
    private final int height;
    private final double textFrameDuration;
    private final double imageFrameDuration;
    private final double titleFrameDuration;
    private final double outroFrameDuration;

    private final Font interRegular;
    private final Font interBold;
    private final BufferedImage logo;
    private final Map<String, BufferedImage> avatarCache = new HashMap<>();

    private int frameCounter = 0;
    private final List<FrameSpec> frames = new ArrayList<>();

    public FrameRenderer(int width, int height,
                         double textFrameDuration, double imageFrameDuration,
                         double titleFrameDuration, double outroFrameDuration) {
        this.width = width;
        this.height = height;
        this.textFrameDuration = textFrameDuration;
        this.imageFrameDuration = imageFrameDuration;
        this.titleFrameDuration = titleFrameDuration;
        this.outroFrameDuration = outroFrameDuration;

        try {
            this.interRegular = loadFont("export-assets/Inter-Regular.ttf");
            this.interBold = loadFont("export-assets/Inter-Bold.ttf");
            this.logo = loadClasspathImage("export-assets/promptle-logo.png");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load export assets", e);
        }
    }

    private Font loadFont(String classpath) throws IOException, FontFormatException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpath)) {
            if (is == null) {
                throw new IOException("Font not found on classpath: " + classpath);
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, is);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font;
        }
    }

    private BufferedImage loadClasspathImage(String classpath) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpath)) {
            if (is == null) {
                throw new IOException("Image not found on classpath: " + classpath);
            }
            return ImageIO.read(is);
        }
    }

    /** Avatar ids are icon-N and map 1:1 to icon-N.png; null if unknown (graceful skip). */
    private BufferedImage avatar(String avatarId) {
        if (avatarId == null) {
            return null;
        }
        return avatarCache.computeIfAbsent(avatarId, id -> {
            try {
                return loadClasspathImage("export-assets/player-icons/" + id + ".png");
            } catch (IOException e) {
                return null;
            }
        });
    }

    // ----- Frames -------------------------------------------------------------

    public void renderTitleFrame(Path tempDir, String originPlayerName, String roomCode) throws IOException {
        BufferedImage img = createBaseImage();
        Graphics2D g = setupGraphics(img);

        int centerX = width / 2;
        int logoSize = 220;
        int logoY = (int) (height * 0.34);
        g.drawImage(logo, centerX - logoSize / 2, logoY, logoSize, logoSize, null);

        g.setFont(tracked(interBold, 30f, 0.2f));
        g.setColor(ACCENT);
        drawCentered(g, "THE TELEPROMPTLE", centerX, logoY + logoSize + 80);

        drawFooter(g);

        g.dispose();
        writeFrame(img, tempDir, titleFrameDuration);
    }

    public void renderTextFrame(Path tempDir, String text, String playerName, String avatarId,
                                boolean isPlaceholder, int step, int total, String role) throws IOException {
        BufferedImage img = createBaseImage();
        Graphics2D g = setupGraphics(img);

        int bodyY = drawHeader(g, playerName, avatar(avatarId), false, role, ACCENT);

        int cardX = MARGIN;
        int cardW = width - 2 * MARGIN;
        int pad = 52;
        int textMaxWidth = cardW - 2 * pad;

        Font textFont = interRegular.deriveFont(38f);
        if (isPlaceholder) {
            textFont = textFont.deriveFont(Font.ITALIC);
        }
        g.setFont(textFont);
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrapText(text != null && !text.isBlank() ? text : "…", fm, textMaxWidth);
        int lineHeight = (int) (fm.getHeight() * 1.18);
        int cardH = lines.size() * lineHeight + 2 * pad;

        // Vertically center the card in the space between header and footer.
        int footerTop = height - 150;
        int cardY = bodyY + Math.max(0, (footerTop - bodyY - cardH) / 2);

        drawCard(g, cardX, cardY, cardW, cardH, 36);

        g.setColor(isPlaceholder ? TEXT_MUTED : TEXT_PRIMARY);
        int textY = cardY + pad + fm.getAscent();
        for (String line : lines) {
            g.drawString(line, cardX + pad, textY);
            textY += lineHeight;
        }

        drawFooter(g);
        g.dispose();
        writeFrame(img, tempDir, textFrameDuration);
    }

    public void renderImageFrame(Path tempDir, byte[] imageBytes, int step, int total) throws IOException {
        BufferedImage img = createBaseImage();
        Graphics2D g = setupGraphics(img);

        // Promptle "drew" the image — header uses the app logo as the speaker.
        int bodyY = drawHeader(g, "Promptle", logo, true, "DREW THIS", ACCENT);

        BufferedImage genImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (genImage != null) {
            int footerTop = height - 150;
            int maxImgWidth = width - 2 * MARGIN;
            int maxImgHeight = footerTop - bodyY;

            double scale = Math.min(
                    (double) maxImgWidth / genImage.getWidth(),
                    (double) maxImgHeight / genImage.getHeight());
            int drawW = (int) (genImage.getWidth() * scale);
            int drawH = (int) (genImage.getHeight() * scale);
            int imgX = (width - drawW) / 2;
            int imgY = bodyY + Math.max(0, (maxImgHeight - drawH) / 2);
            int radius = 32;

            // Soft offset shadow (the app's "sticker" look, in green-tinted black).
            g.setColor(SHADOW);
            g.fill(new RoundRectangle2D.Double(imgX + 14, imgY + 16, drawW, drawH, radius, radius));

            // Rounded-clip the image.
            Shape clip = g.getClip();
            g.setClip(new RoundRectangle2D.Double(imgX, imgY, drawW, drawH, radius, radius));
            g.drawImage(genImage, imgX, imgY, drawW, drawH, null);
            g.setClip(clip);

            // Accent ring.
            g.setColor(ACCENT);
            g.setStroke(new BasicStroke(4f));
            g.draw(new RoundRectangle2D.Double(imgX, imgY, drawW, drawH, radius, radius));
        }

        drawFooter(g);
        g.dispose();
        writeFrame(img, tempDir, imageFrameDuration);
    }

    public void renderOutroFrame(Path tempDir, String roomCode) throws IOException {
        BufferedImage img = createBaseImage();
        Graphics2D g = setupGraphics(img);

        int centerX = width / 2;
        int logoSize = 220;
        int logoY = (int) (height * 0.30);
        g.drawImage(logo, centerX - logoSize / 2, logoY, logoSize, logoSize, null);

        g.setFont(interBold.deriveFont(46f));
        g.setColor(TEXT_PRIMARY);
        drawCentered(g, "Made with Promptle", centerX, logoY + logoSize + 110);

        g.setFont(tracked(interRegular, 26f, 0.1f));
        g.setColor(TEXT_MUTED);
        drawCentered(g, "Play your own round", centerX, logoY + logoSize + 162);

        g.dispose();
        writeFrame(img, tempDir, outroFrameDuration);
    }

    // ----- Shared drawing -----------------------------------------------------

    /** Draws the avatar + name + role header. Returns the Y for the body. */
    private int drawHeader(Graphics2D g, String name, BufferedImage avatarImg, boolean contain,
                           String role, Color ring) {
        drawAvatar(g, avatarImg, MARGIN, TOP, AVATAR, contain, ring);

        int textX = MARGIN + AVATAR + 32;
        g.setFont(interBold.deriveFont(40f));
        g.setColor(TEXT_PRIMARY);
        g.drawString(name != null ? name : "Unknown", textX, TOP + 50);

        g.setFont(tracked(interBold, 22f, 0.14f));
        g.setColor(ACCENT);
        g.drawString(role != null ? role.toUpperCase() : "", textX, TOP + 92);

        return TOP + AVATAR + 56;
    }

    private void drawAvatar(Graphics2D g, BufferedImage avatarImg, int x, int y, int size,
                            boolean contain, Color ring) {
        // Filled identity circle behind the character (matches --avatar-bg).
        g.setColor(AVATAR_BG);
        g.fill(new Ellipse2D.Double(x, y, size, size));

        if (avatarImg != null) {
            Shape clip = g.getClip();
            g.setClip(new Ellipse2D.Double(x, y, size, size));
            if (contain) {
                int pad = (int) (size * 0.18);
                g.drawImage(avatarImg, x + pad, y + pad, size - 2 * pad, size - 2 * pad, null);
            } else {
                g.drawImage(avatarImg, x, y, size, size, null);
            }
            g.setClip(clip);
        }

        // Accent ring covers the clip seam (settled/finished state).
        g.setColor(ring);
        g.setStroke(new BasicStroke(5f));
        g.draw(new Ellipse2D.Double(x + 2.5, y + 2.5, size - 5, size - 5));
    }

    private void drawCard(Graphics2D g, int x, int y, int w, int h, int radius) {
        g.setColor(SHADOW);
        g.fill(new RoundRectangle2D.Double(x + 8, y + 10, w, h, radius, radius));
        g.setColor(SURFACE);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, radius, radius));
        g.setColor(HAIRLINE);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, radius, radius));
    }

    private void drawFooter(Graphics2D g) {
        int logoSize = 40;
        Font font = tracked(interBold, 22f, 0.22f);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        String word = "PROMPTLE";
        int wordW = fm.stringWidth(word);
        int totalW = logoSize + 14 + wordW;
        int x = (width - totalW) / 2;
        int y = height - 76;

        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.42f));
        g.drawImage(logo, x, y - logoSize / 2 - fm.getAscent() / 2 + 4, logoSize, logoSize, null);
        g.setColor(new Color(255, 255, 255, 160));
        g.drawString(word, x + logoSize + 14, y);
        g.setComposite(old);
    }

    private void drawCentered(Graphics2D g, String text, int centerX, int baselineY) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, centerX - fm.stringWidth(text) / 2, baselineY);
    }

    private Font tracked(Font base, float size, float tracking) {
        Map<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.TRACKING, tracking);
        return base.deriveFont(size).deriveFont(attrs);
    }

    // ----- Plumbing -----------------------------------------------------------

    public int getFrameCount() {
        return frameCounter;
    }

    /** Ordered frames with their on-screen display duration (seconds), for the encoder. */
    public List<FrameSpec> getFrames() {
        return frames;
    }

    private BufferedImage createBaseImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return img;
    }

    private Graphics2D setupGraphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private List<String> wrapText(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        String[] words = text.split("\\s+");
        StringBuilder currentLine = new StringBuilder();
        for (String word : words) {
            if (currentLine.isEmpty()) {
                currentLine.append(word);
            } else {
                String candidate = currentLine + " " + word;
                if (fm.stringWidth(candidate) <= maxWidth) {
                    currentLine.append(" ").append(word);
                } else {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder(word);
                }
            }
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }
        return lines;
    }

    private void writeFrame(BufferedImage img, Path tempDir, double duration) throws IOException {
        frameCounter++;
        String filename = String.format("frame-%03d.png", frameCounter);
        Path framePath = tempDir.resolve(filename);
        ImageIO.write(img, "png", framePath.toFile());
        frames.add(new FrameSpec(filename, duration));
    }

    public record FrameSpec(String filename, double duration) {}
}
