package com.vanvatcorporation.doubleclips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.vanvatcorporation.doubleclips.data.editing.TextStyleData;
import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The text styles the user saved ("My styles"), kept in the app's own storage (text-styles.json, the same
 * file name as on Android) so they are there in every project. The built-ins are not stored here (see
 * {@link TextPresets#builtIns()}). Picking a style copies its values into the clip, so deleting one never
 * changes a project.
 */
public final class TextPresetLibrary {

    /** Where the list lives. A file in the app, or memory in a test. */
    public interface Store {
        List<TextStyleData> read() throws IOException;

        void write(List<TextStyleData> styles) throws IOException;
    }

    private final Store store;

    public TextPresetLibrary(Store store) {
        this.store = store;
    }

    private static TextPresetLibrary shared;

    /** The user's library in the app folder. */
    public static synchronized TextPresetLibrary shared() {
        if (shared == null) shared = new TextPresetLibrary(fileStore(new File(StorageHelper.getAppDirectory(), "text-styles.json")));
        return shared;
    }

    /** All saved styles, oldest first. A missing or damaged file just means none. */
    public synchronized List<TextStyleData> load() {
        try {
            List<TextStyleData> list = store.read();
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    /** Saves a copy of {@code look} under a new id and {@code name}; returns the saved style. */
    public synchronized TextStyleData save(TextStyleData look, String name) throws IOException {
        List<TextStyleData> all = load();
        TextStyleData saved = new TextStyleData(look.normalized());
        long stamp = System.currentTimeMillis();
        String id = TextPresets.USER_ID_PREFIX + stamp;
        while (TextPresets.find(all, id) != null) id = TextPresets.USER_ID_PREFIX + (++stamp);
        saved.id = id;
        saved.name = name == null || name.trim().isEmpty() ? "My style" : name.trim();
        saved.author = null;
        saved.withAndroidFields();
        all.add(saved);
        store.write(all);
        return saved;
    }

    public synchronized void delete(String id) throws IOException {
        List<TextStyleData> all = load();
        boolean removed = all.removeIf(d -> id != null && id.equals(d.id));
        if (removed) store.write(all);
    }

    public static boolean isUserStyle(TextStyleData d) {
        return d != null && d.id != null && d.id.startsWith(TextPresets.USER_ID_PREFIX);
    }

    // ---- the file --------------------------------------------------------------------------

    private static final Type LIST_TYPE = new TypeToken<List<TextStyleData>>() { }.getType();

    public static Store fileStore(File file) {
        return new Store() {
            @Override
            public List<TextStyleData> read() throws IOException {
                if (!file.isFile()) return new ArrayList<>();
                try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                    return new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson(reader, LIST_TYPE);
                } catch (RuntimeException e) {
                    throw new IOException("damaged styles file", e);
                }
            }

            @Override
            public void write(List<TextStyleData> styles) throws IOException {
                File parent = file.getAbsoluteFile().getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Couldn't create " + parent);
                // Write beside it, then swap: a crash mid-write never leaves half a library.
                File tmp = new File(parent, file.getName() + ".tmp");
                Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create();
                try (Writer writer = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                    gson.toJson(styles, LIST_TYPE, writer);
                }
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        };
    }
}
