package com.vanvatcorporation.doubleclips.drive;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * "Hide assets from this person" (iOS: BlockedAuthors): kept on this computer only, by display name, and applied when the
 * lists are drawn. Saved in the app folder (assets_blocked_authors.json) so it survives restarts. Settings > Hidden content lists
 * the names (as the poster wrote them) and shows them again.
 */
public final class BlockedAuthors {
    private BlockedAuthors() {}

    private static final Type LIST = new TypeToken<List<String>>() { }.getType();
    /** lower case (how names are compared) -> the name as it was hidden (how it is shown). */
    private static final TreeMap<String, String> names = new TreeMap<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static boolean loaded;

    private static File file() { return new File(StorageHelper.getAppDirectory(), "assets_blocked_authors.json"); }

    private static String key(String owner) { return owner == null ? "" : owner.trim().toLowerCase(Locale.ROOT); }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file();
            if (!f.isFile()) return;
            List<String> list = new Gson().fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), LIST);
            if (list != null) for (String s : list) if (!key(s).isEmpty()) names.put(key(s), s.trim());
        } catch (IOException | RuntimeException ignored) {
            // an unreadable list is an empty list: nobody is hidden, nothing breaks
        }
    }

    private static void save() {
        try {
            Files.write(file().toPath(), new Gson().toJson(new ArrayList<>(names.values()), LIST).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // best effort: the block still holds until the app closes
        }
    }

    public static synchronized boolean contains(String owner) {
        load();
        return !key(owner).isEmpty() && names.containsKey(key(owner));
    }

    /** The hidden names as they were written, in alphabetical order. */
    public static synchronized List<String> names() {
        load();
        return new ArrayList<>(names.values());
    }

    public static void block(String owner) {
        if (key(owner).isEmpty()) return;
        synchronized (BlockedAuthors.class) {
            load();
            names.put(key(owner), owner.trim());
            save();
        }
        fire();
    }

    public static void unblock(String owner) {
        synchronized (BlockedAuthors.class) {
            load();
            names.remove(key(owner));
            save();
        }
        fire();
    }

    /** Shows everyone again. */
    public static void unblockAll() {
        synchronized (BlockedAuthors.class) {
            load();
            names.clear();
            save();
        }
        fire();
    }

    /** Someone was hidden or shown again: the lists on screen redraw. Called on the thread that changed it. */
    public static void addListener(Runnable r) { listeners.add(r); }

    public static void removeListener(Runnable r) { listeners.remove(r); }

    private static void fire() {
        for (Runnable r : listeners) r.run();
    }
}
