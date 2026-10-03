package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop adapter for the plain-Java animation classes ({@link ClipAnimationLoader},
 * {@link ClipAnimationPacks}): finds the animation files and gives them to those classes.
 * Android's version of this class reads the same files from its assets folder.
 * <p>
 * Bundled animations are classpath resources: {@code /animations/in/*.json} ("in" animations)
 * and {@code /animations/out/*.json} ("out" animations); the folder a file is in must match the
 * direction it declares. A classpath folder can't be listed once the app is packed into a jar,
 * so the bundled files are named in {@code /animations/index.txt} (one {@code in/name.json} or
 * {@code out/name.json} per line; blank lines and lines starting with # are ignored). To add a
 * bundled animation, drop its .json into the folder AND add its line to that list.
 * <p>
 * Installed packs live in {@code <app directory>/animation_packs/<packId>/}, where the app
 * directory is {@link StorageHelper#getAppDirectory()}. The OpenGL export runs in a separate
 * JVM, which resolves the same folder, so packs installed in the app are seen by the export.
 */
public final class ClipAnimationAssets {

    public static final String RESOURCE_DIR = "/animations/";
    public static final String INDEX_FILE = "index.txt";
    public static final String PACKS_DIR = "animation_packs";

    private static boolean builtInsLoaded = false;
    private static boolean packsLoaded = false;

    private ClipAnimationAssets() {}

    /** Loads the bundled animations, then the installed packs. Idempotent; this is what the app / export calls. */
    public static synchronized List<String> loadAll() {
        List<String> problems = new ArrayList<>(loadBuiltIns());
        problems.addAll(loadInstalledPacks());
        return problems;
    }

    public static File packsDir() {
        return new File(StorageHelper.getAppDirectory(), PACKS_DIR);
    }

    /** One bundled file waiting to be loaded. */
    private static final class Pending {
        final String name;                       // path under /animations/, e.g. in/unfold.json
        final ClipAnimation.Direction expected;  // the folder it is in

        Pending(String name, ClipAnimation.Direction expected) {
            this.name = name;
            this.expected = expected;
        }
    }

    /**
     * Loads every bundled animation into the registry. Safe to call as often as you like (it only
     * does the work once it has found the index). Returns human-readable problems, one per bad file -
     * an empty list means everything loaded. A bad file never stops the others from loading.
     */
    public static synchronized List<String> loadBuiltIns() {
        List<String> errors = new ArrayList<>();
        if (builtInsLoaded) return errors;

        String index;
        try {
            index = readResource(INDEX_FILE);
        } catch (IOException e) {
            errors.add("can't read " + RESOURCE_DIR + INDEX_FILE + ": " + e.getMessage());
            return errors;
        }
        List<Pending> pending = new ArrayList<>();
        for (String line : index.split("\\R")) {
            String n = line.trim();
            if (n.isEmpty() || n.startsWith("#")) continue;
            ClipAnimation.Direction dir = null;
            for (ClipAnimation.Direction d : ClipAnimation.Direction.values()) {
                if (n.startsWith(d.json + "/")) dir = d;
            }
            if (dir == null || !n.endsWith(".json")) {
                errors.add(INDEX_FILE + ": '" + n + "' must be in/<file>.json or out/<file>.json - not loaded");
                continue;
            }
            pending.add(new Pending(n, dir));
        }
        if (pending.isEmpty()) {
            errors.add(RESOURCE_DIR + INDEX_FILE + " lists no animations - none available");
            return errors;
        }

        // Files are independent except "mirrorOf" ones, which need their base registered first (an "out"
        // animation often mirrors an "in" one). Rather than work out an order, keep retrying the ones that
        // were only waiting on a base until a whole pass makes no progress.
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            List<Pending> stillPending = new ArrayList<>();
            for (Pending p : pending) {
                try {
                    ClipAnimationLoader.register(readResource(p.name), p.name, true, p.expected);
                    progress = true;
                } catch (ClipAnimationLoader.FormatException e) {
                    if (e.missingMirrorBase) {
                        stillPending.add(p);
                    } else {
                        errors.add(e.getMessage()); // permanently bad: report once, don't retry
                        progress = true;
                    }
                } catch (IOException e) {
                    errors.add(p.name + ": can't read file: " + e.getMessage());
                    progress = true;
                }
            }
            pending = stillPending;
        }
        for (Pending p : pending) errors.add(p.name + ": its \"mirrorOf\" animation was never loaded");

        builtInsLoaded = true;
        return errors;
    }

    /** Loads the installed animation packs (after the built-ins). Idempotent; installs/removals keep the registry current themselves. */
    public static synchronized List<String> loadInstalledPacks() {
        if (packsLoaded) return new ArrayList<>();
        List<String> problems = new ArrayList<>(loadBuiltIns()); // pack ids are checked against the built-ins
        packsLoaded = true;
        problems.addAll(ClipAnimationPacks.loadInstalled(packsDir()));
        return problems;
    }

    /** Installs the animation pack .zip the user picked. Throws a PackException whose message is fit to show. */
    public static synchronized ClipAnimationPacks.InstallResult importPack(File zipFile)
            throws ClipAnimationPacks.PackException {
        loadAll(); // so the new pack is checked against everything already installed
        try (InputStream in = new FileInputStream(zipFile)) {
            return ClipAnimationPacks.install(packsDir(), in);
        } catch (IOException e) {
            throw new ClipAnimationPacks.PackException("Couldn't read that file: " + e.getMessage());
        }
    }

    public static synchronized List<ClipAnimationPacks.PackInfo> installedPacks() {
        loadAll();
        return ClipAnimationPacks.listInstalled(packsDir());
    }

    public static synchronized void removePack(String packId) throws ClipAnimationPacks.PackException {
        loadAll();
        ClipAnimationPacks.uninstall(packsDir(), packId);
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = ClipAnimationAssets.class.getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) throw new IOException("resource not found: " + RESOURCE_DIR + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            // read one byte past the limit so an oversized file is rejected by the loader, not truncated into valid JSON
            int limit = ClipAnimationLoader.MAX_JSON_CHARS * 4 + 1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) break;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
