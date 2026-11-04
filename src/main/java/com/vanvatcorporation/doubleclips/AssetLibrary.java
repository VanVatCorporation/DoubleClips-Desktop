package com.vanvatcorporation.doubleclips;

import com.vanvatcorporation.doubleclips.data.ProjectData;
import com.vanvatcorporation.doubleclips.data.storage.StorageHelper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a downloaded community asset becomes (iOS: AssetLibrary):
 * <ul>
 *   <li>effect: installed in the app ({@code <app folder>/assets/effects/<id>.json}) and registered at every start like a built-in style;</li>
 *   <li>pack: an animation pack, through the same strict installer as Settings > Animation packs;</li>
 *   <li>font: copied into a project's Fonts folder (the text editor lists it);</li>
 *   <li>video / image / audio: go through the editor's own import (Clips folder + preview proxies), see EditorWindow.</li>
 * </ul>
 * Everything downloaded is untrusted: effects and packs go through their normal parsers (which validate everything and register
 * nothing when a file is bad), fonts through the font check, and nothing is run. No UI here.
 */
public final class AssetLibrary {
    private AssetLibrary() {}

    /** A downloaded file that can't be installed; the message is written for the user. */
    public static final class InstallException extends Exception {
        private static final long serialVersionUID = 1L;
        public InstallException(String message) { super(message); }
    }

    private static final int MAX_EFFECT_BYTES = 256 * 1024;

    public static File effectsDir() {
        return new File(new File(StorageHelper.getAppDirectory(), "assets"), "effects");
    }

    // ---- effects -----------------------------------------------------------------------------------------

    /** Installs a downloaded effect style and returns its name. */
    public static String installEffect(File file) throws InstallException {
        try {
            if (file.length() > MAX_EFFECT_BYTES) throw new InstallException("This effect file is too big.");
            String text = readUtf8(file);
            ClipAnimationAssets.loadAll();   // the built-ins must be known first: a download never replaces one of them
            EffectStyle style = EffectStyleLoader.parse(text, "community asset");
            String id = style.getId();
            File target = new File(effectsDir(), safeName(id) + ".json");
            if (EffectStyleLoader.get(id) != null && !target.isFile()) {
                throw new InstallException("An effect with this id already exists.");
            }
            File dir = effectsDir();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new InstallException("Couldn't create the effects folder.");
            File part = new File(dir, target.getName() + ".part");
            Files.write(part.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            EffectStyleLoader.register(text, "community asset");
            return style.getName();
        } catch (ClipAnimationLoader.FormatException e) {
            throw new InstallException(e.getMessage());
        } catch (IOException e) {
            throw new InstallException("Couldn't install the effect: " + e.getMessage());
        }
    }

    /**
     * Registers the installed effects (called once the built-in styles are loaded, at every start; the OpenGL export's own JVM
     * does it too, so an exported video shows them). Returns one line per bad file; a bad file never stops the others.
     */
    public static List<String> loadInstalledEffects() {
        List<String> problems = new ArrayList<>();
        File[] files = effectsDir().listFiles((d, n) -> n.toLowerCase().endsWith(".json"));
        if (files == null) return problems;
        Arrays.sort(files);
        for (File f : files) {
            try {
                EffectStyleLoader.register(readUtf8(f), "community asset");
            } catch (ClipAnimationLoader.FormatException | IOException e) {
                problems.add("installed effect " + f.getName() + ": " + e.getMessage());
            }
        }
        return problems;
    }

    // ---- packs -------------------------------------------------------------------------------------------

    /** Installs a downloaded animation pack and returns its name. */
    public static String installPack(File file) throws InstallException {
        try {
            return ClipAnimationAssets.importPack(file).pack.name;
        } catch (ClipAnimationPacks.PackException e) {
            throw new InstallException(e.getMessage());
        }
    }

    // ---- fonts -------------------------------------------------------------------------------------------

    /**
     * Copies a downloaded font into the project's Fonts folder and returns its family name (or the file name). A different file that
     * has the same name is never overwritten (another clip may use it): the new one becomes name-1.ttf.
     */
    public static String addFont(File file, ProjectData project) throws InstallException {
        String lower = file.getName().toLowerCase();
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
            throw new InstallException("The editor can use .ttf and .otf fonts; this font collection isn't supported yet.");
        }
        if (!TextLayoutEngine.isLoadableFont(file)) throw new InstallException("That file isn't a font this editor can read.");
        String dir = TextStyle.fontsDirOf(project);
        if (dir == null) throw new InstallException("There is no project to add the font to.");
        try {
            File folder = new File(dir);
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("could not create " + folder);
            String name = ProjectLibrary.safeFileName(file.getName());
            File target = new File(folder, name);
            int n = 1;
            while (target.exists() && target.length() != file.length()) {
                int dot = name.lastIndexOf('.');
                target = new File(folder, (dot > 0 ? name.substring(0, dot) : name) + "-" + n++ + (dot > 0 ? name.substring(dot) : ""));
            }
            if (!target.exists()) Files.copy(file.toPath(), target.toPath());
            String family = TextLayoutEngine.familyOfFile(target);
            return family != null ? family : target.getName();
        } catch (IOException e) {
            throw new InstallException("Could not add the font: " + e.getMessage());
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private static String readUtf8(File file) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file.toPath()))).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("This isn't an effect file.");
        }
    }

    /** A style id as a file name: the id is the file's identity, so only characters every file system accepts. */
    private static String safeName(String id) {
        String s = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() || s.equals(".") || s.equals("..") ? "effect" : s;
    }
}
