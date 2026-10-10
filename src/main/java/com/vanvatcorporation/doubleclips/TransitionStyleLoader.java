package com.vanvatcorporation.doubleclips;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transition styles as JSON - a port of iOS's TransitionStyleLoader. A style is a DATA file in
 * {@code animations/transitions/<id>.json}, built from the same channels and the same three curve kinds as a
 * clip animation ({@link ClipAnimationLoader}), so nothing in it can execute anything:
 * <pre>
 * {
 *   "schema": 1,
 *   "id": "glitchblur",              // [a-z0-9_-]{1,64}: the style key a transition stores
 *   "name": "Glitch Blur",
 *   "description": "...",            // optional, 256 characters at most
 *   "defaultDuration": 1.1,          // optional, seconds, (0, 30]; default 0.5
 *   "cut": 0.76,                     // where A is replaced by B, 0.05 ... 0.95 of the window (default 0.5)
 *   "referenceFrames": 100,          // only needed by "frames" knots
 *   "from": { "channels": { ... } }, // the look of A over the WHOLE window (p = 0 ... 1); A is drawn for p &lt; cut
 *   "to":   { "channels": { ... } }  // the look of B, same p; B is drawn for p &gt;= cut
 * }
 * </pre>
 * There is no cross dissolve: one of the two pictures is drawn at any moment, each through the normal clip
 * pipeline with its side's channels applied on top of the clip's own properties AND its In / Out animation
 * ({@link ClipAnimationFrame#merged}). So a style is just "what happens to A before the cut, what happens to
 * B after it". Validation is the clip-animation one (strict JSON, bounded size, unknown keys / channels rejected,
 * values inside the channel's range); a bad file throws {@link ClipAnimationLoader.FormatException} and
 * registers nothing. Thread-safe: the render threads read the registry while the UI may register files.
 *
 * Bundled styles are listed in {@code /animations/index.txt} as {@code transitions/<file>.json} and loaded by
 * {@link ClipAnimationAssets}, like the bundled in / out animations.
 */
public final class TransitionStyleLoader {

    /** Folder (and index.txt prefix) of the bundled style files. */
    public static final String ASSET_FOLDER = "transitions";

    private static final int MAX_NAME_CHARS = 64;
    private static final int MAX_NOTE_CHARS = 256;

    private static final Map<String, TransitionStyle> STYLES = new ConcurrentHashMap<>();
    private static volatile boolean builtInsLoaded = false;
    private static boolean loadAttempted = false; // guarded by the class lock: try the bundled files once, never per frame

    private TransitionStyleLoader() {}

    // ---- lookup --------------------------------------------------------------------------

    /**
     * The data-driven style stored under this key, or null when the key is a hard-coded style (or null / empty).
     * Loads the bundled files first if nobody has yet.
     */
    public static TransitionStyle get(String id) {
        if (id == null || id.isEmpty()) return null;
        ensureLoaded();
        return STYLES.get(id);
    }

    /** Every registered style, sorted by id. */
    public static List<TransitionStyle> list() {
        ensureLoaded();
        List<TransitionStyle> out = new ArrayList<>(STYLES.values());
        Collections.sort(out, new Comparator<TransitionStyle>() {
            @Override public int compare(TransitionStyle a, TransitionStyle b) { return a.getId().compareTo(b.getId()); }
        });
        return out;
    }

    /** Reads the bundled files through ClipAnimationAssets the first time anyone asks (a failed attempt is not repeated). */
    private static void ensureLoaded() {
        if (builtInsLoaded) return;
        synchronized (TransitionStyleLoader.class) {
            if (builtInsLoaded || loadAttempted) return;
            loadAttempted = true;
            ClipAnimationAssets.loadBuiltIns(); // idempotent; ends in markBuiltInsLoaded()
        }
    }

    /** Called by {@link ClipAnimationAssets} once it has attempted the bundled style files. */
    static void markBuiltInsLoaded() { builtInsLoaded = true; }

    // ---- registering ---------------------------------------------------------------------

    /** Parses, validates and registers one file (replacing a style with the same id). */
    public static TransitionStyle register(String json, String source) throws ClipAnimationLoader.FormatException {
        TransitionStyle style = parse(json, source);
        STYLES.put(style.getId(), style);
        return style;
    }

    // ---- parse + validate ----------------------------------------------------------------

    private static final String[] TOP_KEYS = {
            "schema", "id", "name", "description", "author", "defaultDuration", "cut", "referenceFrames", "from", "to"
    };

    /** Parses and validates without registering. {@code source} (e.g. "transitions/glitchblur.json") prefixes any error. */
    public static TransitionStyle parse(String json, String source) throws ClipAnimationLoader.FormatException {
        try {
            return parseInternal(json);
        } catch (ClipAnimationLoader.FormatException e) {
            if (source == null || source.isEmpty()) throw e;
            throw new ClipAnimationLoader.FormatException(source + ": " + e.getMessage());
        }
    }

    private static TransitionStyle parseInternal(String json) throws ClipAnimationLoader.FormatException {
        if (json == null) throw new ClipAnimationLoader.FormatException("no data");
        if (json.length() > ClipAnimationLoader.MAX_JSON_CHARS) {
            throw new ClipAnimationLoader.FormatException("file is larger than " + ClipAnimationLoader.MAX_JSON_CHARS + " characters");
        }
        final Map<String, Object> top = ClipAnimationLoader.parseObject(json);
        ClipAnimationLoader.allowKeys(top, "$", TOP_KEYS);

        double schema = ClipAnimationLoader.reqNumber(top, "schema", "$");
        if (schema != ClipAnimationLoader.SUPPORTED_SCHEMA) {
            throw new ClipAnimationLoader.FormatException("$.schema: unsupported schema " + ClipAnimationLoader.fmt(schema)
                    + " (this app reads " + ClipAnimationLoader.SUPPORTED_SCHEMA + ")");
        }
        final String id = ClipAnimationLoader.reqString(top, "id", "$", 64);
        if (!id.matches("[a-z0-9_-]{1,64}") || "none".equals(id)) {
            throw new ClipAnimationLoader.FormatException("$.id: must be 1-64 characters of a-z 0-9 _ - and not 'none'");
        }
        String nameValue = ClipAnimationLoader.optString(top, "name", "$", MAX_NAME_CHARS);
        final String name = (nameValue == null || nameValue.isEmpty()) ? id : nameValue;
        String description = ClipAnimationLoader.optString(top, "description", "$", MAX_NOTE_CHARS);
        ClipAnimationLoader.optString(top, "author", "$", MAX_NOTE_CHARS);

        double duration = ClipAnimationLoader.DEFAULT_DURATION_FALLBACK;
        if (top.containsKey("defaultDuration")) {
            duration = ClipAnimationLoader.reqNumber(top, "defaultDuration", "$");
            if (!(duration > 0.0 && duration <= ClipAnimationLoader.MAX_DEFAULT_DURATION)) {
                throw new ClipAnimationLoader.FormatException("$.defaultDuration: must be > 0 and <= "
                        + (int) ClipAnimationLoader.MAX_DEFAULT_DURATION + " seconds");
            }
        }
        double cut = 0.5;
        if (top.containsKey("cut")) {
            cut = ClipAnimationLoader.reqNumber(top, "cut", "$");
            if (!(cut >= 0.05 && cut <= 0.95)) {
                throw new ClipAnimationLoader.FormatException("$.cut: must be between 0.05 and 0.95");
            }
        }
        int referenceFrames = 0;
        if (top.containsKey("referenceFrames")) {
            double rf = ClipAnimationLoader.reqNumber(top, "referenceFrames", "$");
            if (rf != Math.rint(rf) || rf < 1 || rf > 10000) {
                throw new ClipAnimationLoader.FormatException("$.referenceFrames: must be a whole number 1-10000");
            }
            referenceFrames = (int) rf;
        }

        ClipAnimation from = parseSide(top, "from", ClipAnimation.Direction.OUT, id, name, (float) duration, referenceFrames);
        ClipAnimation to = parseSide(top, "to", ClipAnimation.Direction.IN, id, name, (float) duration, referenceFrames);
        return new TransitionStyle(id, name, description, (float) duration, cut, from, to);
    }

    private static ClipAnimation parseSide(Map<String, Object> top, String key, ClipAnimation.Direction direction,
                                           String id, String name, float duration, int referenceFrames)
            throws ClipAnimationLoader.FormatException {
        String path = "$." + key;
        Map<String, Object> object = ClipAnimationLoader.asObject(top.get(key), path);
        ClipAnimationLoader.allowKeys(object, path, "channels");
        Map<String, Object> channelObjects = ClipAnimationLoader.asObject(object.get("channels"), path + ".channels");
        if (channelObjects.isEmpty()) {
            throw new ClipAnimationLoader.FormatException(path + ".channels: at least one channel is required");
        }
        EnumMap<ClipAnimation.Channel, ClipAnimation.Curve> curves = new EnumMap<>(ClipAnimation.Channel.class);
        for (Map.Entry<String, Object> e : channelObjects.entrySet()) {
            String channelPath = path + ".channels." + e.getKey();
            ClipAnimation.Channel channel = ClipAnimation.Channel.fromJson(e.getKey());
            if (channel == null) throw new ClipAnimationLoader.FormatException(channelPath + ": unknown channel");
            curves.put(channel, ClipAnimationLoader.parseCurve(ClipAnimationLoader.asObject(e.getValue(), channelPath),
                    channel, referenceFrames, channelPath));
        }
        return new ClipAnimation(id + "." + key, name, direction, duration, curves, false, referenceFrames);
    }
}
