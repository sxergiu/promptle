package com.app.promptle.export.service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FrameRenderer {

    private static final Color BG_COLOR = new Color(0x1a, 0x1a, 0x1a);
    private static final Color BUBBLE_BG = new Color(0x2a, 0x2a, 0x2a);
    private static final Color BUBBLE_BORDER = new Color(0x3a, 0x3a, 0x3a);
    private static final Color GREEN_ACCENT = new Color(0x62, 0x82, 0x66);
    private static final Color WHITE = Color.WHITE;

    private static final Map<String, String> AVATAR_ID_TO_FILENAME = Map.ofEntries(
            Map.entry("icon-1", "red-saxon"),
            Map.entry("icon-2", "saxon"),
            Map.entry("icon-3", "brown-saxon"),
            Map.entry("icon-4", "dark-wiz"),
            Map.entry("icon-5", "red-wiz"),
            Map.entry("icon-6", "blue-wiz"),
            Map.entry("icon-7", "green-wiz"),
            Map.entry("icon-8", "boy-cone"),
            Map.entry("icon-9", "cat-cone"),
            Map.entry("icon-10", "wiz-cone")
    );

    private final int width;
    private final int height;
    private final int textFrameDuration;
    private final int imageFrameDuration;
    private final int titleFrameDuration;
    private final int outroFrameDuration;

    private final Font interRegular;
    private final Font interBold;
    private final BufferedImage logo;
    private final Map<String, BufferedImage> avatarMap;

    private int frameCounter = 0;
    private final List<FrameEntry> frameEntries = new ArrayList<>();

    public FrameRenderer(int width, int height,
                         int textFrameDuration, int imageFrameDuration,
                         int titleFrameDuration, int outroFrameDuration) {
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
            this.avatarMap = loadAvatars();
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

    private Map<String, BufferedImage> loadAvatars() throws IOException {
        Map<String, BufferedImage> map = new HashMap<>();
        for (Map.Entry<String, String> entry : AVATAR_ID_TO_FILENAME.entrySet()) {
            String path = "export-assets/player-icons/" + entry.getValue() + ".png";
            map.put(entry.getKey(), loadClasspathImage(path));
        }
        return map;
    }

    public void renderTitleFrame(Path tempDir, String originPlayerName, String roomCode) throws IOException {
        BufferedImage img = createBaseImage();
        Graphics2D g = setupGraphics(img);

        int centerX = width / 2;
        int logoSize = 48;
        int logoY = height / 2 - 80;
        g.drawImage(logo, centerX - logoSize / 2, logoY, logoSize, logoSize, null);

        Font titleFont = interBold.deriveFont(18f);
        g.setFont(titleFont);
        g.setColor(WHITE);
        String chainText = "Chain started by " + originPlayerName;
        FontMetrics fm = g.getFontMetrics();
        g.drawString(chainText, centerX - fm.stringWidth(chainText) / 2, logoY + logoSize + 35);

        Font subtitleFont = interRegular.deriveFont(14f);
        g.setFont(subtitleFont);
        g.setColor(new Color(255, 255, 255, 165));
        String roomText = "Room: " + roomCode;
        fm = g.getFontMetrics();
        g.drawString(roomText, centerX - fm.stringWidth(roomText) / 2, logoY + logoSize + 60);

        drawBranding(g);
        g.dispose();
        writeFrame(img, tempDir, titleFrameDuration);
    }

    public void renderTextFrame(Path tempDir, String text, String playerName,
                                String avatarId, boolean isPlaceholder) throws IOException {
        BufferedImage img = createBaseImage();
        Graphics2D g = setupGraphics(img);

        int leftMargin = 40;
        int topY = 50;
        int avatarSize = 48;

        // Draw circular avatar
        BufferedImage avatarImg = avatarMap.get(avatarId);
        if (avatarImg != null) {
            Shape clip = g.getClip();
            g.setClip(new Ellipse2D.Double(leftMargin, topY, avatarSize, avatarSize));
            g.drawImage(avatarImg, leftMargin, topY, avatarSize, avatarSize, null);
            g.setClip(clip);
        }

        // Player name label
        Font nameFont = interBold.deriveFont(11f);
        g.setFont(nameFont);
        g.setColor(new Color(255, 255, 255, 166));
        String displayName = playerName != null ? playerName.toUpperCase() : "UNKNOWN";
        g.drawString(displayName, leftMargin + avatarSize + 12, topY + 30);

        // Chat bubble
        int bubbleX = leftMargin;
        int bubbleY = topY + avatarSize + 16;
        int bubbleMaxWidth = width - 2 * leftMargin;
        int bubblePadding = 20;
        int textMaxWidth = bubbleMaxWidth - 2 * bubblePadding;

        Font textFont = interRegular.deriveFont(16f);
        if (isPlaceholder) {
            textFont = textFont.deriveFont(Font.ITALIC);
        }
        g.setFont(textFont);
        FontMetrics fm = g.getFontMetrics();

        List<String> lines = wrapText(text != null ? text : "", fm, textMaxWidth);
        int lineHeight = fm.getHeight();
        int textBlockHeight = lines.size() * lineHeight;
        int bubbleHeight = textBlockHeight + 2 * bubblePadding;

        // Draw bubble background
        RoundRectangle2D bubble = new RoundRectangle2D.Double(
                bubbleX, bubbleY, bubbleMaxWidth, bubbleHeight, 16, 16);
        g.setColor(BUBBLE_BG);
        g.fill(bubble);
        g.setColor(BUBBLE_BORDER);
        g.setStroke(new BasicStroke(1f));
        g.draw(bubble);

        // Draw text
        float textAlpha = isPlaceholder ? 0.5f : 1.0f;
        g.setColor(new Color(1f, 1f, 1f, textAlpha));
        int textY = bubbleY + bubblePadding + fm.getAscent();
        for (String line : lines) {
            g.drawString(line, bubbleX + bubblePadding, textY);
            textY += lineHeight;
        }

        drawBranding(g);
        g.dispose();
        writeFrame(img, tempDir, textFrameDuration);
    }

    public void renderImageFrame(Path tempDir, byte[] imageBytes) throws IOException {
        BufferedImage img = createBaseImage();
        Graphics2D g = setupGraphics(img);

        int rightMargin = 40;
        int topY = 50;
        int logoSize = 48;

        // Right-aligned: label + logo
        Font labelFont = interBold.deriveFont(11f);
        g.setFont(labelFont);
        g.setColor(GREEN_ACCENT);
        String label = "PROMPTLE";
        FontMetrics fm = g.getFontMetrics();
        int labelWidth = fm.stringWidth(label);

        int logoX = width - rightMargin - logoSize;
        int labelX = logoX - labelWidth - 8;
        g.drawString(label, labelX, topY + 30);
        g.drawImage(logo, logoX, topY, logoSize, logoSize, null);

        // Draw generated image
        BufferedImage genImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (genImage != null) {
            int maxImgWidth = 460;
            int maxImgHeight = 460;
            int imgY = topY + logoSize + 20;

            double scale = Math.min(
                    (double) maxImgWidth / genImage.getWidth(),
                    (double) maxImgHeight / genImage.getHeight());
            int drawWidth = (int) (genImage.getWidth() * scale);
            int drawHeight = (int) (genImage.getHeight() * scale);
            int imgX = (width - drawWidth) / 2;

            // Green border
            g.setColor(GREEN_ACCENT);
            g.setStroke(new BasicStroke(2f));
            g.drawRect(imgX - 2, imgY - 2, drawWidth + 4, drawHeight + 4);

            g.drawImage(genImage, imgX, imgY, drawWidth, drawHeight, null);
        }

        drawBranding(g);
        g.dispose();
        writeFrame(img, tempDir, imageFrameDuration);
    }

    public void renderOutroFrame(Path tempDir) throws IOException {
        BufferedImage img = createBaseImage();
        Graphics2D g = setupGraphics(img);

        int centerX = width / 2;
        int logoSize = 64;
        int logoY = height / 2 - 50;
        g.drawImage(logo, centerX - logoSize / 2, logoY, logoSize, logoSize, null);

        Font outroFont = interRegular.deriveFont(16f);
        g.setFont(outroFont);
        g.setColor(new Color(255, 255, 255, 200));
        String outroText = "Made with Promptle";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(outroText, centerX - fm.stringWidth(outroText) / 2, logoY + logoSize + 30);

        g.dispose();
        writeFrame(img, tempDir, outroFrameDuration);
    }

    public int getFrameCount() {
        return frameCounter;
    }

    public void writeFrameList(Path tempDir) throws IOException {
        Path framesFile = tempDir.resolve("frames.txt");
        try (PrintWriter pw = new PrintWriter(framesFile.toFile())) {
            for (FrameEntry entry : frameEntries) {
                pw.println("file '" + entry.filename + "'");
                pw.println("duration " + entry.duration);
            }
        }
    }

    private BufferedImage createBaseImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BG_COLOR);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return img;
    }

    private Graphics2D setupGraphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        return g;
    }

    private void drawBranding(Graphics2D g) {
        int centerX = width / 2;
        int brandingY = height - 30;

        // Thin line
        g.setColor(new Color(255, 255, 255, 38));
        g.setStroke(new BasicStroke(0.5f));
        g.drawLine(40, brandingY - 15, width - 40, brandingY - 15);

        // Branding text
        Font brandFont = interRegular.deriveFont(10f);
        g.setFont(brandFont);
        g.setColor(new Color(255, 255, 255, 77));
        String brandText = "Promptle";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(brandText, centerX - fm.stringWidth(brandText) / 2, brandingY);
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

    private void writeFrame(BufferedImage img, Path tempDir, int duration) throws IOException {
        frameCounter++;
        String filename = String.format("frame-%03d.png", frameCounter);
        Path framePath = tempDir.resolve(filename);
        ImageIO.write(img, "png", framePath.toFile());
        frameEntries.add(new FrameEntry(filename, duration));
    }

    private record FrameEntry(String filename, int duration) {}
}
