package com.vanvatcorporation.doubleclips.i18n;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The languages the desktop app can show (Settings > General > Language, next to the Appearance setting). Same idea as
 * the iOS and Android AppLanguage.
 *
 * <ul>
 *   <li>The choice is stored in {@link com.vanvatcorporation.doubleclips.data.AppSettings} under {@code app_language}:
 *       {@link #SYSTEM} (follow the computer) or a code from {@link #SUPPORTED}.</li>
 *   <li>English is the language the app is written in: the English text is the key (see {@link I18n}), so English needs no
 *       file and every string a language doesn't translate simply stays English.</li>
 *   <li>To add a language: add a row to {@link #SUPPORTED} and a {@code /i18n/<code>.strings} resource (copy {@code vi.strings}
 *       and translate the right-hand sides). The iOS {@code Localizable.strings} files use the same format.</li>
 * </ul>
 */
public final class AppLanguage {

    /** The choice "follow the computer's language". */
    public static final String SYSTEM = "system";
    /** What {@link #SYSTEM} falls back to when none of the computer's languages is one of ours. */
    public static final String FALLBACK = "en";

    /** One language the app can show. */
    public static final class Language {
        /** Language code, also the name of the resource {@code /i18n/<code>.strings}. */
        public final String code;
        /** The language's own name: never translated, so anyone can always find theirs. */
        public final String nativeName;

        Language(String code, String nativeName) {
            this.code = code;
            this.nativeName = nativeName;
        }

        @Override
        public String toString() {
            return nativeName;
        }
    }

    /** Every language the app can show. English is the one the strings are written in. */
    public static final List<Language> SUPPORTED = Collections.unmodifiableList(java.util.Arrays.asList(
            new Language("en", "English"),
            new Language("vi", "Tiếng Việt")
    ));

    private AppLanguage() {}

    /** For the picker: alphabetical by the language's own name ("System Default" goes first, added by the caller). */
    public static List<Language> sortedForPicker() {
        List<Language> sorted = new ArrayList<>(SUPPORTED);
        final Collator collator = Collator.getInstance(Locale.ENGLISH);
        sorted.sort((a, b) -> collator.compare(a.nativeName, b.nativeName));
        return sorted;
    }

    public static boolean isSupported(String code) {
        for (Language l : SUPPORTED) if (l.code.equals(code)) return true;
        return false;
    }

    /**
     * The code to show for a stored choice: the choice itself when it is a supported language, else (for {@link #SYSTEM} or
     * anything unknown) the first of the computer's preferred languages that the app has, else {@link #FALLBACK}.
     *
     * @param preferred the computer's languages in order of preference, as codes or tags ("vi", "vi-VN", "vi_VN")
     */
    public static String resolve(String choice, List<String> preferred) {
        if (choice != null && !SYSTEM.equals(choice) && isSupported(choice)) return choice;
        if (preferred != null) {
            for (String tag : preferred) {
                if (tag == null) continue;
                String base = tag.replace('_', '-').trim();
                int dash = base.indexOf('-');
                if (dash > 0) base = base.substring(0, dash);
                base = base.toLowerCase(Locale.ROOT);
                if (isSupported(base)) return base;
            }
        }
        return FALLBACK;
    }

    /** The computer's languages as the JVM knows them (the OS setting), most preferred first. */
    public static List<String> systemLanguages() {
        List<String> out = new ArrayList<>();
        out.add(Locale.getDefault().toLanguageTag());
        String user = System.getProperty("user.language");
        if (user != null && !user.isEmpty()) out.add(user);
        return out;
    }
}
