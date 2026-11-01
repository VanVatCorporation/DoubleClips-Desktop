package com.vanvatcorporation.doubleclips.auth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/** Plain-Java checks of {@link TemplateSearchParse} and {@link TemplateSearchHistory}: prints "ok" or fails. */
public final class TemplateSearchSelfTest {

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static void main(String[] args) {
        // the server's shapes: a bare array, {items}, {data}, {results}, {keywords}; strings or objects
        check(TemplateSearchParse.suggestions(Arrays.asList("tet", "Tet ", " birthday ", "", null, "TET")).equals(Arrays.asList("tet", "birthday")),
                "bare array, trimmed, de-duplicated: " + TemplateSearchParse.suggestions(Arrays.asList("tet", "Tet ", " birthday ", "", null, "TET")));
        check(TemplateSearchParse.suggestions(obj("items", Arrays.asList("a", obj("query", "b"), obj("text", "c"), obj("keyword", "d"), obj("title", "e"), obj("x", "no")))).equals(Arrays.asList("a", "b", "c", "d", "e")),
                "object rows");
        check(TemplateSearchParse.suggestions(obj("data", Arrays.asList("x"))).size() == 1 && TemplateSearchParse.suggestions(obj("keywords", Arrays.asList("k"))).size() == 1
                && TemplateSearchParse.suggestions(obj("results", Arrays.asList("r"))).size() == 1, "wrapper keys");
        check(TemplateSearchParse.suggestions("nonsense").isEmpty() && TemplateSearchParse.suggestions(null).isEmpty() && TemplateSearchParse.suggestions(obj("items", 5)).isEmpty(), "bad input = nothing");

        List<TrendingSearch> t = TemplateSearchParse.trending(Arrays.asList(
                obj("query", "Tet 2027", "count", 12345.0, "hot", true),
                obj("text", "birthday", "searchCount", "870"),
                obj("keyword", "Birthday", "score", 5.0),      // same text again: dropped
                "plain string",
                obj("title", "intro", "score", 42.0, "hot", "yes")));  // "yes" is not a boolean: not hot
        check(t.size() == 4, "trending size " + t.size());
        check(t.get(0).text.equals("Tet 2027") && t.get(0).count == 12345 && t.get(0).hot, "first");
        check(t.get(1).count == 870 && !t.get(1).hot, "string count");
        check(t.get(2).text.equals("plain string") && t.get(2).count == null, "string row has no count");
        check(t.get(3).count == 42 && !t.get(3).hot, "score as count, strict boolean");

        check("87".equals(TemplateSearchParse.compactCount(87)) && "1.2K".equals(TemplateSearchParse.compactCount(1234)) && "2.5M".equals(TemplateSearchParse.compactCount(2_500_000)), "compact counts");

        // local suggestions: accent-insensitive, prefix matches first, history before titles, at most 10
        check(TemplateSearchParse.fold("Tết Đẹp").equals("tet dep"), "fold: " + TemplateSearchParse.fold("Tết Đẹp"));
        List<String> local = TemplateSearchParse.localSuggestions("tet", Arrays.asList("my Tết vlog", "tet holiday"), Arrays.asList(new TrendingSearch("Tết 2027", 1, false)),
                Arrays.asList("Chúc mừng Tết", "unrelated", "tet holiday"));
        check(local.equals(Arrays.asList("tet holiday", "Tết 2027", "my Tết vlog", "Chúc mừng Tết")), "local order: " + local);
        check(TemplateSearchParse.localSuggestions("  ", Arrays.asList("a"), new ArrayList<>(), new ArrayList<>()).isEmpty(), "blank = none");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add("song " + i);
        check(TemplateSearchParse.localSuggestions("song", many, new ArrayList<>(), new ArrayList<>()).size() == 10, "at most 10");
        check(TemplateSearchParse.typedPrefixLength("Tet 2027", "tet") == 3 && TemplateSearchParse.typedPrefixLength("my tet", "tet") == 0 && TemplateSearchParse.typedPrefixLength("a", "abc") == 0, "typed prefix");

        // history: newest first, repeats move up, capped, survives a restart
        Preferences node = Preferences.userRoot().node("com/vanvatcorporation/doubleclips/selftest-" + System.nanoTime());
        try {
            TemplateSearchHistory h = new TemplateSearchHistory(node);
            h.add("one"); h.add("two"); h.add("ONE ");
            check(h.items().equals(Arrays.asList("ONE", "two")), "repeat moves up: " + h.items());
            for (int i = 0; i < 30; i++) h.add("q" + i);
            check(h.items().size() == TemplateSearchHistory.LIMIT && h.items().get(0).equals("q29"), "capped at 20, newest first");
            h.add("   "); h.add(null);
            check(h.items().size() == TemplateSearchHistory.LIMIT, "blank ignored");
            int[] changes = {0};
            h.addListener(() -> changes[0]++);
            h.remove("q29"); h.remove("never there"); h.add("last");
            check(changes[0] == 2 && h.items().get(0).equals("last") && !h.items().contains("q29"), "remove/add notify");
            check(new TemplateSearchHistory(node).items().equals(h.items()), "kept between runs");
            h.clear();
            check(h.items().isEmpty() && new TemplateSearchHistory(node).items().isEmpty(), "cleared");
        } finally {
            try { node.removeNode(); } catch (Exception ignored) { }
        }
        System.out.println("ok");
    }
}
