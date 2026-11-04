package com.vanvatcorporation.doubleclips.auth;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.vanvatcorporation.doubleclips.data.TemplateData;
import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Templates the user hid ("Hide this template" in the preview): kept on this computer only, by template id, and left out of the
 * Templates tab and the template search. Settings > Hidden content lists them and shows them again. The title and author are kept
 * so the list says what was hidden. Saved in the app folder (hidden_templates.json).
 */
public final class HiddenTemplates {
    private HiddenTemplates() {}

    /** One hidden template, as shown in Settings. */
    public static final class Entry {
        public String id;
        public String title;
        public String author;
    }

    private static final Type LIST = new TypeToken<List<Entry>>() { }.getType();
    private static final Map<String, Entry> entries = new LinkedHashMap<>();   // by lower-case id, newest last
    private static final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();
    private static boolean loaded;

    private static File file() { return new File(StorageHelper.getAppDirectory(), "hidden_templates.json"); }

    private static String key(String id) { return id == null ? "" : id.trim().toLowerCase(Locale.ROOT); }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file();
            if (!f.isFile()) return;
            List<Entry> list = new Gson().fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), LIST);
            if (list != null) for (Entry e : list) if (e != null && !key(e.id).isEmpty()) entries.put(key(e.id), e);
        } catch (IOException | RuntimeException ignored) {
            // an unreadable list is an empty list: nothing is hidden, nothing breaks
        }
    }

    private static void save() {
        try {
            Files.write(file().toPath(), new Gson().toJson(new ArrayList<>(entries.values()), LIST).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // best effort: the hide still holds until the app closes
        }
    }

    public static synchronized boolean contains(String templateId) {
        load();
        return !key(templateId).isEmpty() && entries.containsKey(key(templateId));
    }

    /** The hidden templates, in the order they were hidden. */
    public static synchronized List<Entry> all() {
        load();
        return new ArrayList<>(entries.values());
    }

    public static void hide(TemplateData data) {
        if (data == null || key(data.getTemplateId()).isEmpty()) return;
        Entry e = new Entry();
        e.id = data.getTemplateId();
        e.title = data.getTemplateTitle();
        e.author = data.getTemplateAuthor();
        synchronized (HiddenTemplates.class) {
            load();
            entries.put(key(e.id), e);
            save();
        }
        fire(false);
    }

    public static void unhide(String templateId) {
        synchronized (HiddenTemplates.class) {
            load();
            entries.remove(key(templateId));
            save();
        }
        fire(true);
    }

    public static void unhideAll() {
        synchronized (HiddenTemplates.class) {
            load();
            entries.clear();
            save();
        }
        fire(true);
    }

    /** Runs on the thread that changed the list; the flag is true when something was shown again (the feed then reloads to bring it back). */
    public static void addListener(Consumer<Boolean> r) { listeners.add(r); }

    public static void removeListener(Consumer<Boolean> r) { listeners.remove(r); }

    private static void fire(boolean unhid) {
        for (Consumer<Boolean> r : listeners) r.accept(unhid);
    }
}
