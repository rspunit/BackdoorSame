package dickbuilder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GUI-independent logic for positioning q/p/W using RuneScape Plain 12 metrics. */
public final class RuneScapeChatLogic {
    int osrsChatBlue = 0xFF00FFFF; // OSRS Chat color
    public static final int W_OFFSET_FROM_Q = 4; // calibrated from known-good examples
    public static final int SPACE_CODE_POINT = 32;

    private final Map<Integer, Glyph> glyphs;
    private final int ascent;
    private final int maxAscent;
    private final int maxDescent;

    public record Glyph(int codePoint, int advance, int leftBearing, int width,
                        int topBearing, int height, int[] pixels) {
        public Glyph {
            pixels = pixels.clone();
            if (pixels.length != width * height) {
                throw new IllegalArgumentException("Pixel count does not match glyph dimensions for U+"
                        + Integer.toHexString(codePoint).toUpperCase());
            }
        }

        @Override public int[] pixels() { return pixels.clone(); }
    }

    /** errorPx > 0 => W is too far right; errorPx < 0 => W is too far left. */
    public record Alignment(int qX, int pX, int targetWX, int actualWX, int errorPx) {
        public boolean exact() { return errorPx == 0; }
        public int pixelsToMoveRight() { return -errorPx; }
        public float spacingUnitsToTarget(int spaceAdvance) {
            return targetWX / (float) spaceAdvance;
        }
        public float correctionInSpaces(int spaceAdvance) {
            return -errorPx / (float) spaceAdvance;
        }
    }

    public RuneScapeChatLogic(Map<Integer, Glyph> glyphs, int ascent, int maxAscent, int maxDescent) {
        this.glyphs = Map.copyOf(glyphs);
        this.ascent = ascent;
        this.maxAscent = maxAscent;
        this.maxDescent = maxDescent;
        if (!this.glyphs.containsKey(SPACE_CODE_POINT)) {
            throw new IllegalArgumentException("Font definitions contain no space glyph");
        }
    }

    /** Loads the supplied RuneScape font-definition JSON without requiring a JSON library. */
    public static RuneScapeChatLogic load(Path jsonPath) throws IOException {
        String json = Files.readString(jsonPath, StandardCharsets.UTF_8);
        int ascent = intField(json, "ascent");
        int maxAscent = intField(json, "maxAscent");
        int maxDescent = intField(json, "maxDescent");
        Pattern object = Pattern.compile("\\{([^{}]*)}");
        Pattern cp = Pattern.compile("\\\"codePoint\\\"\\s*:\\s*(\\d+)");
        Map<Integer, Glyph> out = new HashMap<>();
        Matcher objects = object.matcher(json);
        while (objects.find()) {
            String s = objects.group(1);
            Matcher cpm = cp.matcher(s);
            if (!cpm.find()) continue;
            int codePoint = Integer.parseInt(cpm.group(1));
            int width = intField(s, "width");
            int height = intField(s, "height");
            out.put(codePoint, new Glyph(codePoint,
                    intField(s, "advance"), intField(s, "leftBearing"), width,
                    intField(s, "topBearing"), height, intArrayField(s, "pixels")));
        }
        if (out.isEmpty()) throw new IOException("No glyph definitions found in " + jsonPath);
        return new RuneScapeChatLogic(out, ascent, maxAscent, maxDescent);
    }

    private static int intField(String object, String name) throws IOException {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*(-?\\d+)").matcher(object);
        if (!m.find()) throw new IOException("Missing glyph field: " + name);
        return Integer.parseInt(m.group(1));
    }

    private static int[] intArrayField(String object, String name) throws IOException {
        Matcher m = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*\\[([^]]*)]").matcher(object);
        if (!m.find()) throw new IOException("Missing glyph array field: " + name);
        String body = m.group(1).trim();
        if (body.isEmpty()) return new int[0];
        String[] parts = body.split("\\s*,\\s*");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) values[i] = Integer.parseInt(parts[i]);
        return values;
    }

    public int getAdvance(int codePoint) {
        Glyph g = glyphs.get(codePoint);
        if (g == null) throw new IllegalArgumentException("No glyph for U+" + Integer.toHexString(codePoint).toUpperCase());
        return g.advance();
    }

    public int getSpaceAdvance() { return getAdvance(SPACE_CODE_POINT); }

    /** Rendered advance width in pixels. No kerning is present in the supplied definitions. */
    public int getStringWidth(String text) {
        Objects.requireNonNull(text, "text");
        int x = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            x += getAdvance(cp);
            i += Character.charCount(cp);
        }
        return x;
    }

    /** X coordinate of the glyph's drawing start, including its left bearing. */
    public int getVisibleX(String text, int charIndex) {
        Objects.requireNonNull(text, "text");
        if (charIndex < 0 || charIndex >= text.length()) throw new IndexOutOfBoundsException(charIndex);
        int cp = text.codePointAt(charIndex);
        Glyph g = glyphs.get(cp);
        if (g == null) throw new IllegalArgumentException("No glyph for U+" + Integer.toHexString(cp).toUpperCase());
        return getStringWidth(text.substring(0, charIndex)) + g.leftBearing();
    }

    /** Finds the q in a literal "q p" sequence. */
    public int findQP(String top) {
        Objects.requireNonNull(top, "top");
        return top.indexOf("q p");
    }

    /** Finds the first uppercase W in the lower line. */
    public int findW(String bottom) {
        Objects.requireNonNull(bottom, "bottom");
        return bottom.indexOf('W');
    }

    /** Exact visible-pixel target for W: calibrated as q visible-X + 4. */
    public int getWTargetPixels(String top) {
        int qi = findQP(top);
        if (qi < 0) throw new IllegalArgumentException("Top text must contain literal sequence \"q p\"");
        return getVisibleX(top, qi) + W_OFFSET_FROM_Q;
    }

    /** Number of Plain-12 space advances from origin to the target W position. May be fractional. */
    public float getWPos(String top) {
        return getWTargetPixels(top) / (float) getSpaceAdvance();
    }

    /** Compares actual W position in bottom line against the exact target derived from top line. */
    public Alignment checkAlignment(String top, String bottom) {
        int qi = findQP(top);
        if (qi < 0) throw new IllegalArgumentException("Top text must contain literal sequence \"q p\"");
        int wi = findW(bottom);
        if (wi < 0) throw new IllegalArgumentException("Bottom text must contain uppercase W");

        int qX = getVisibleX(top, qi);
        int pX = getVisibleX(top, qi + 2);
        int target = qX + W_OFFSET_FROM_Q;
        int actual = getVisibleX(bottom, wi);
        return new Alignment(qX, pX, target, actual, actual - target);
    }

    /** Human-readable status suitable for a GUI label. */
    public String describeAlignment(String top, String bottom) {
        Alignment a = checkAlignment(top, bottom);
        if (a.exact()) return "EXACT (0 px)";
        return a.errorPx() > 0
                ? a.errorPx() + " px too far RIGHT"
                : (-a.errorPx()) + " px too far LEFT";
    }

    public int getAscent() { return ascent; }
    public int getMaxAscent() { return maxAscent; }
    public int getMaxDescent() { return maxDescent; }
    public int getLineHeight() { return maxAscent + maxDescent; }

    /**
     * Renders one line using the raw bitmap data from the RuneScape font definition.
     * The returned image has a transparent background and uses exactly one image pixel
     * per RuneScape font pixel. The default text color is opaque white.
     */
    public BufferedImage renderText(String text) {
        return renderText(text, 0xFFFFFFFF);
    }

    /** Renders one line with an ARGB text color, e.g. 0xFFFFFF00 for yellow. */
    public BufferedImage renderText(String text, int argb) {
        Objects.requireNonNull(text, "text");
        int width = Math.max(1, getStringWidth(text));
        BufferedImage image = new BufferedImage(width, getLineHeight(), BufferedImage.TYPE_INT_ARGB);
        renderOnto(image, text, 0, 0, argb);
        return image;
    }

    /**
     * Renders the two chat messages on consecutive font-height rows.
     * Both lines share x=0, which makes q/p/W alignment visible exactly as measured.
     */
    public BufferedImage renderMessages(String top, String bottom) {
        return renderMessages(top, bottom, 0xFFFFFFFF, 0);
    }

    /**
     * Renders two messages with a configurable transparent gap between them.
     * lineGap is in raw RuneScape pixels and must be >= 0.
     */
    public BufferedImage renderMessages(String top, String bottom, int argb, int lineGap) {
        Objects.requireNonNull(top, "top");
        Objects.requireNonNull(bottom, "bottom");
        if (lineGap < 0) throw new IllegalArgumentException("lineGap must be >= 0");
        int width = Math.max(1, Math.max(getStringWidth(top), getStringWidth(bottom)));
        int lineHeight = getLineHeight();
        BufferedImage image = new BufferedImage(width, lineHeight * 2 + lineGap, BufferedImage.TYPE_INT_ARGB);
        renderOnto(image, top, 0, 0, argb);
        renderOnto(image, bottom, 0, lineHeight + lineGap, argb);
        return image;
    }

    /**
     * Draws text into an existing image. x/y identify the top-left of the font's
     * max-ascent/max-descent cell, not the glyph bitmap itself.
     */
    public void renderOnto(BufferedImage target, String text, int x, int y, int argb) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(text, "text");
        int penX = x;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            Glyph glyph = glyphs.get(cp);
            if (glyph == null) {
                throw new IllegalArgumentException("No glyph for U+" + Integer.toHexString(cp).toUpperCase());
            }
            int drawX = penX + glyph.leftBearing();
            int drawY = y + glyph.topBearing();
            int[] pixels = glyph.pixels();
            for (int gy = 0; gy < glyph.height(); gy++) {
                for (int gx = 0; gx < glyph.width(); gx++) {
                    if (pixels[gy * glyph.width() + gx] != 0) {
                        int px = drawX + gx;
                        int py = drawY + gy;
                        if (px >= 0 && px < target.getWidth() && py >= 0 && py < target.getHeight()) {
                            target.setRGB(px, py, argb);
                        }
                    }
                }
            }
            penX += glyph.advance();
            i += Character.charCount(cp);
        }
    }

    /** Nearest-neighbor integer scaling for a crisp GUI preview. */
    public static BufferedImage scaleNearest(BufferedImage source, int scale) {
        Objects.requireNonNull(source, "source");
        if (scale < 1) throw new IllegalArgumentException("scale must be >= 1");
        if (scale == 1) return source;
        BufferedImage out = new BufferedImage(source.getWidth() * scale,
                source.getHeight() * scale, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int c = source.getRGB(x, y);
                for (int sy = 0; sy < scale; sy++) {
                    for (int sx = 0; sx < scale; sx++) {
                        out.setRGB(x * scale + sx, y * scale + sy, c);
                    }
                }
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java RuneScapeChatLogic RuneScape-Plain-12.json");
            System.exit(2);
        }
        RuneScapeChatLogic logic = load(Path.of(args[0]));
        String top = "I wish I had a q pet but I haven't got it yet";
        String bottom = "Sure one day We'll get lucky";
        Alignment a = logic.checkAlignment(top, bottom);
        System.out.println(a);
        System.out.println(logic.describeAlignment(top, bottom));
        System.out.println("W spacing units: " + logic.getWPos(top));
        if (!a.exact()) throw new AssertionError("Known-good calibration failed: " + a);
    }
    
    public String getCharactersWithAdvance(int advance) {
    StringBuilder result = new StringBuilder();

    glyphs.values().stream()
            .filter(g -> g.advance() == advance)
            .filter(g -> g.codePoint() >= 32 && g.codePoint() <= 126)
            .sorted((a, b) -> Integer.compare(a.codePoint(), b.codePoint()))
            .forEach(g -> result.appendCodePoint(g.codePoint()));

    return result.toString();
    }
    
    public String describeWidthSuggestions(int pixels) {
    int width = Math.abs(pixels);
    String chars = getCharactersWithAdvance(width);

    if (chars.isEmpty()) {
        return "No single character has an advance of " + width + " px";
    }

    return width + " px characters: " + chars;
    }
}