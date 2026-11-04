package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.auth.TrendingSearch;
import com.vanvatcorporation.doubleclips.drive.DoubleClipsDrive;
import javafx.application.Platform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Assets hot searches ("Trending") and the "You may like" list, shared by the search bar's ticker and the search window
 * (iOS: AssetSearchHints; the Templates tab has the same in TemplateSearchHints). Loaded once and again after
 * {@link #REFRESH_AFTER_MS} (or when forced); a failure keeps what is already shown, so a server without the search migration
 * just means empty lists and the phone-side suggestions. Use on the JavaFX thread; listeners run there too.
 */
public final class AssetSearchHints {

    public static final long REFRESH_AFTER_MS = 5 * 60 * 1000;

    private static AssetSearchHints instance;

    private List<TrendingSearch> trending = new ArrayList<>();
    private List<String> forYou = new ArrayList<>();
    private long loadedAt;
    private boolean loading;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static synchronized AssetSearchHints getInstance() {
        if (instance == null) instance = new AssetSearchHints();
        return instance;
    }

    private AssetSearchHints() {}

    public List<TrendingSearch> trending() { return Collections.unmodifiableList(trending); }

    public List<String> forYou() { return Collections.unmodifiableList(forYou); }

    public void addListener(Runnable r) { if (r != null) listeners.addIfAbsent(r); }

    public void removeListener(Runnable r) { listeners.remove(r); }

    public void refresh(boolean force) {
        if (loading) return;
        if (!force && loadedAt != 0 && System.currentTimeMillis() - loadedAt < REFRESH_AFTER_MS) return;
        loading = true;
        Thread t = new Thread(() -> {
            List<TrendingSearch> hot = null;
            List<String> mine = null;
            try {
                hot = DoubleClipsDrive.assets.trending(10);
            } catch (IOException | RuntimeException ignored) {
                // keeps the old list
            }
            try {
                mine = DoubleClipsDrive.assets.suggest("", 6);
            } catch (IOException | RuntimeException ignored) {
                // keeps the old list
            }
            final List<TrendingSearch> newHot = hot;
            final List<String> newMine = mine;
            Platform.runLater(() -> {
                loading = false;
                if (newHot != null) trending = newHot;
                if (newMine != null) forYou = newMine;
                if (newHot != null || newMine != null) {
                    loadedAt = System.currentTimeMillis();
                    for (Runnable r : listeners) r.run();
                }
            });
        }, "asset-search-hints");
        t.setDaemon(true);
        t.start();
    }
}
