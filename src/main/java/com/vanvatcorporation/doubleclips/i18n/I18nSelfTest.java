package com.vanvatcorporation.doubleclips.i18n;

import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Plain-Java checks of {@link AppLanguage} and {@link I18n}: prints "ok" or fails. */
public final class I18nSelfTest {

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        // the picker: alphabetical by the language's own name
        check(AppLanguage.sortedForPicker().get(0).code.equals("en") && AppLanguage.sortedForPicker().get(1).code.equals("vi"), "picker order");

        // resolve: a supported choice wins, "system" follows the computer, anything else is English
        check(AppLanguage.resolve("vi", Arrays.asList("en-US")).equals("vi"), "explicit choice");
        check(AppLanguage.resolve("system", Arrays.asList("vi-VN", "en")).equals("vi"), "system -> first supported (vi-VN)");
        check(AppLanguage.resolve("system", Arrays.asList("fr_FR", "vi")).equals("vi"), "system skips unsupported");
        check(AppLanguage.resolve("system", Arrays.asList("fr", "de")).equals("en"), "system, none ours -> English");
        check(AppLanguage.resolve("xx", Arrays.asList("vi")).equals("vi"), "unknown stored choice acts as system");
        check(AppLanguage.resolve(null, null).equals("en"), "null");

        // the .strings format
        Map<String, String> t = I18n.parse(new StringReader(
                "/* header */\n\"Cancel\" = \"Hủy\";\n// note\n\"Say \\\"hi\\\"\" = \"Nói \\\"xin chào\\\"\";\n"
                        + "\"Line\\nbreak\" = \"Dòng\\nmới\";\n\"Dot \\u2022 here\" = \"Chấm \\u2022 đây\";\n\"A = B;\" = \"x = y;\";\n"));
        check("Hủy".equals(t.get("Cancel")), "plain");
        check("Nói \"xin chào\"".equals(t.get("Say \"hi\"")), "escaped quotes");
        check("Dòng\nmới".equals(t.get("Line\nbreak")), "newline");
        check("Chấm \u2022 đây".equals(t.get("Dot \u2022 here")), "unicode escape");
        check("x = y;".equals(t.get("A = B;")), "= and ; inside a string");
        check(t.size() == 5, "5 entries, got " + t.size());

        // the real Vietnamese table: loads, and every key keeps the placeholders of its English text
        I18n.setLanguage("vi");
        check("vi".equals(I18n.currentCode()), "vi active");
        check(!"Cancel".equals(I18n.t("Cancel")), "Cancel is translated: " + I18n.t("Cancel"));
        check("Some text nobody translated".equals(I18n.t("Some text nobody translated")), "missing = English");
        int[] changes = {0};
        Runnable r = () -> changes[0]++;
        I18n.addChangeListener(r);
        I18n.setLanguage("vi");
        check(changes[0] == 0, "same language: no notification");
        I18n.setLanguage("en");
        check(changes[0] == 1 && "Cancel".equals(I18n.t("Cancel")), "back to English notifies once");
        I18n.setLanguage("zz");
        check("en".equals(I18n.currentCode()), "unknown code = English");
        I18n.removeChangeListener(r);

        // format: a bad translation falls back to the English pattern instead of throwing
        check("3 of 5".equals(I18n.f("%d of %d", 3, 5)), "format");
        System.out.println("ok");
    }
}
