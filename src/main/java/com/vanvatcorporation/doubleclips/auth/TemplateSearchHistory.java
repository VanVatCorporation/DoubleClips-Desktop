package com.vanvatcorporation.doubleclips.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

/** The searches this computer submitted, newest first, at most {@link #LIMIT}. Kept in the user preferences. */
public final class TemplateSearchHistory {

    public static final int LIMIT = 20;
    private static final String KEY = "templateSearchHistory";
    private static final String ASSETS_KEY = "assetSearchHistory";
    private static final int MAX_QUERY_LENGTH = 100;

    private static TemplateSearchHistory instance;
    private static TemplateSearchHistory assetsInstance;

    private final Preferences prefs;
    private final String key;
    private final List<String> items = new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static synchronized TemplateSearchHistory getInstance() {
        if (instance == null) {
            instance = new TemplateSearchHistory(Preferences.userRoot().node("com/vanvatcorporation/doubleclips"));
        }
        return instance;
    }

    /** The searches made in the Assets tab: a list of their own, kept next to the Templates one. */
    public static synchronized TemplateSearchHistory assets() {
        if (assetsInstance == null) {
            assetsInstance = new TemplateSearchHistory(Preferences.userRoot().node("com/vanvatcorporation/doubleclips"), ASSETS_KEY);
        }
        return assetsInstance;
    }

    /** Public so a test can use a node of its own. */
    public TemplateSearchHistory(Preferences prefs) {
        this(prefs, KEY);
    }

    private TemplateSearchHistory(Preferences prefs, String key) {
        this.prefs = prefs;
        this.key = key;
        String stored = prefs.get(key, "");
        for (String line : stored.split("\n")) {
            String q = line.trim();
            if (!q.isEmpty() && items.size() < LIMIT) items.add(q);
        }
    }

    public List<String> items() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    /** Puts a query first (a repeat moves up instead of doubling). */
    public void add(String query) {
        String q = query == null ? "" : query.trim().replace('\n', ' ');
        if (q.isEmpty()) return;
        if (q.length() > MAX_QUERY_LENGTH) q = q.substring(0, MAX_QUERY_LENGTH);
        final String added = q;
        items.removeIf(x -> x.equalsIgnoreCase(added));
        items.add(0, added);
        while (items.size() > LIMIT) items.remove(items.size() - 1);
        changed();
    }

    public void remove(String query) {
        if (items.remove(query)) changed();
    }

    public void clear() {
        if (!items.isEmpty()) {
            items.clear();
            changed();
        }
    }

    public void addListener(Runnable r) { if (r != null) listeners.addIfAbsent(r); }

    public void removeListener(Runnable r) { listeners.remove(r); }

    private void changed() {
        prefs.put(key, String.join("\n", items));
        for (Runnable r : listeners) r.run();
    }
}
