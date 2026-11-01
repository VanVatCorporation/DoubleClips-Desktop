package com.vanvatcorporation.doubleclips.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Text in the language the user chose (see {@link AppLanguage}). The English text IS the key: {@code I18n.t("Cancel")} is
 * "Cancel" in English and whatever {@code /i18n/<code>.strings} says for it in another language; a string the language doesn't
 * have shows in English, so a half-translated language never shows a blank or a key.
 *
 * <p>The files use the iOS {@code Localizable.strings} format: {@code "English text" = "Translation";}, with {@code \n},
 * {@code \t}, {@code \"}, {@code \\} and {@code \\uXXXX} escapes, and {@code /* ... *}{@code /} or {@code //} comments.
 *
 * <p>{@link #f} is for text with values: the English key is a Java format pattern ({@code "%s of %d"}) and a translation may
 * reorder the values with {@code %2$s ... %1$s}. Use it instead of gluing a translated sentence to a value with +.
 *
 * <p>Screens read their text when they are built: a screen that is open keeps the language it was built in, so the main window
 * rebuilds itself when the language changes ({@link #addChangeListener}) and other windows show the new language the next time
 * they open.
 */
public final class I18n {

    private static volatile String code = AppLanguage.FALLBACK;
    private static volatile Map<String, String> table = Collections.emptyMap();
    private static final Map<String, Map<String, String>> loaded = new HashMap<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private I18n() {}

    /** {@code key} (the English text) in the current language; the key itself when there is no translation. */
    public static String t(String key) {
        if (key == null) return null;
        String value = table.get(key);
        return value != null ? value : key;
    }

    /** {@link #t} then {@link String#format} with the current language's number rules. */
    public static String f(String key, Object... args) {
        try {
            return String.format(Locale.forLanguageTag(code), t(key), args);
        } catch (java.util.IllegalFormatException e) {
            // a translation with a broken placeholder must not crash the screen: fall back to the English pattern
            return String.format(Locale.forLanguageTag(code), key, args);
        }
    }

    /** The code of the language shown right now ("en", "vi"...). */
    public static String currentCode() {
        return code;
    }

    /**
     * Switches the language ({@code languageCode} is an already resolved code, see {@link AppLanguage#resolve}) and tells the
     * listeners when it actually changed. An unknown code means English.
     */
    public static void setLanguage(String languageCode) {
        String next = AppLanguage.isSupported(languageCode) ? languageCode : AppLanguage.FALLBACK;
        boolean changed = !next.equals(code);
        table = tableFor(next);
        code = next;
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(next)); // numbers and dates formatted in code follow it
        if (changed) {
            for (Runnable r : listeners) {
                try {
                    r.run();
                } catch (RuntimeException ignored) {
                    // one screen failing to refresh must not stop the others
                }
            }
        }
    }

    public static void addChangeListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public static void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static synchronized Map<String, String> tableFor(String languageCode) {
        Map<String, String> cached = loaded.get(languageCode);
        if (cached != null) return cached;
        Map<String, String> result = Collections.emptyMap();
        if (!AppLanguage.FALLBACK.equals(languageCode)) { // English is the keys themselves
            try (InputStream in = I18n.class.getResourceAsStream("/i18n/" + languageCode + ".strings")) {
                if (in != null) result = parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                System.err.println("LOG Could not read the " + languageCode + " strings: " + e.getMessage());
            }
        }
        loaded.put(languageCode, result);
        return result;
    }

    /** Reads a {@code .strings} table. Lines that aren't {@code "key" = "value";} (comments, blanks) are skipped. */
    static Map<String, String> parse(Reader reader) throws IOException {
        StringBuilder all = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = reader.read(buf)) > 0) all.append(buf, 0, n);
        String s = all.toString();
        Map<String, String> out = new HashMap<>();
        int i = 0, len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < len && s.charAt(i + 1) == '*') { // block comment
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? len : end + 2;
            } else if (c == '/' && i + 1 < len && s.charAt(i + 1) == '/') { // line comment
                int end = s.indexOf('\n', i);
                i = end < 0 ? len : end + 1;
            } else if (c == '"') {
                int[] pos = {i};
                String key = quoted(s, pos);
                i = skipSpace(s, pos[0]);
                if (i < len && s.charAt(i) == '=') {
                    i = skipSpace(s, i + 1);
                    if (i < len && s.charAt(i) == '"') {
                        pos[0] = i;
                        String value = quoted(s, pos);
                        out.put(key, value);
                        i = pos[0];
                    }
                }
            } else {
                i++;
            }
        }
        return out;
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Reads the quoted string that starts at pos[0] (on its opening quote); pos[0] ends after the closing quote. */
    private static String quoted(String s, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int i = pos[0] + 1;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'u':
                    case 'U':
                        if (i + 4 <= s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            } catch (NumberFormatException ex) {
                                sb.append('\\').append(e);
                            }
                        } else {
                            sb.append('\\').append(e);
                        }
                        break;
                    default: sb.append(e); // \" \\ and anything else: the character itself
                }
            } else {
                sb.append(c);
            }
        }
        pos[0] = i;
        return sb.toString();
    }
}
