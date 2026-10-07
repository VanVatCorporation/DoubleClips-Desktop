package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.ProjectLibrary;
import com.vanvatcorporation.doubleclips.ProjectLibrary.Item;
import com.vanvatcorporation.doubleclips.ProjectLibrary.Kind;
import com.vanvatcorporation.doubleclips.ProjectLibrary.LibraryException;
import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.ClipType;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.helper.MediaHelper;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import javafx.geometry.Insets;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the Media tab's buttons and right-click menu do: add a file to the timeline, rename it, replace it, delete
 * it (or all the unused ones), make a solid-colour image. The file work and the changes to the clips are in
 * {@link ProjectLibrary}; this only asks the questions and reports back, as the iOS panel does.
 * <p>
 * Rename, replace and delete change files on disk, which earlier undo steps cannot bring back, so those clear
 * the undo history.
 */
public final class MediaLibraryActions {

    /** What the editor lends to the actions. */
    public interface Host {
        Window window();

        String projectPath();

        Timeline timeline();

        float playhead();

        Track selectedTrack();

        /** Adds {@code clip} to track {@code trackIndex} (making tracks as needed) as one undoable step, selects it and moves the playhead to its end. */
        void addClip(Clip clip, int trackIndex);

        /** The timeline changed: redraw it, save the project. */
        void timelineChanged();

        /** Draw the Media tab again from the files on disk. */
        void reloadLibrary();

        void clearHistory();

        /** Builds the preview proxies for a file (replace makes the old ones stale), in the background with a progress dialog. */
        void rebuildProxies(File file, ClipType type, MediaHelper.MediaInfo info, Runnable done);

        void toast(String text);
    }

    private final Host host;

    public MediaLibraryActions(Host host) {
        this.host = host;
    }

    private void error(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.initOwner(host.window());
        alert.showAndWait();
    }

    private static ProjectLibrary.Probe toProbe(Kind kind, MediaHelper.MediaInfo info) {
        return new ProjectLibrary.Probe(kind, info.duration, info.width, info.height, info.hasAudio);
    }

    // ---- add to the timeline -------------------------------------------------------------------------

    private static boolean isFree(Track track, float start, float length) {
        for (Clip c : track.clips) {
            if (c.startTime < start + length && c.startTime + c.duration > start) return false;
        }
        return true;
    }

    /**
     * Adds the file at the playhead: on the selected track if there is one, otherwise on the first track that is
     * free there, otherwise on a new track. (Not an error when nothing is selected.)
     */
    public void addToTimeline(Item item) {
        Kind kind = item.kind;
        new Thread(() -> {
            MediaHelper.MediaInfo info = MediaHelper.probeMediaInfo(item.file.getAbsolutePath());
            Platform.runLater(() -> place(item, kind, info));
        }, "media-probe").start();
    }

    private void place(Item item, Kind kind, MediaHelper.MediaInfo info) {
        Timeline timeline = host.timeline();
        float length = Math.max(0.5f, info.duration);
        float start = Math.max(0f, host.playhead());
        int trackIndex;
        Track selected = host.selectedTrack();
        if (selected != null && timeline.tracks.contains(selected)) {
            trackIndex = timeline.tracks.indexOf(selected);
        } else {
            trackIndex = timeline.tracks.size(); // a new track unless one is free
            for (int i = 0; i < timeline.tracks.size(); i++) {
                if (isFree(timeline.tracks.get(i), start, length)) {
                    trackIndex = i;
                    break;
                }
            }
        }
        Clip clip = new Clip(item.name, start, length, trackIndex, kind.clipType(),
                info.hasAudio || kind == Kind.AUDIO, info.width, info.height);
        host.addClip(clip, trackIndex);
        host.toast("Added to Track " + (trackIndex + 1));
    }

    // ---- rename ------------------------------------------------------------------------------------------

    public void rename(Item item) {
        String ext = item.name.contains(".") ? item.name.substring(item.name.lastIndexOf('.') + 1) : "";
        String stem = ext.isEmpty() ? item.name : item.name.substring(0, item.name.length() - ext.length() - 1);
        TextInputDialog ask = new TextInputDialog(stem);
        ask.initOwner(host.window());
        ask.setTitle("Rename");
        ask.setHeaderText("Rename " + item.name);
        ask.setContentText("Name:");
        Optional<String> typed = ask.showAndWait();
        if (!typed.isPresent()) return;
        try {
            String newName = ProjectLibrary.validatedName(typed.get(), ext);
            if (newName.equals(item.name)) return;
            ProjectLibrary.rename(item.name, newName, host.projectPath());
            ProjectLibrary.renameInTimeline(host.timeline(), item.name, newName);
            host.clearHistory();
            host.timelineChanged();
            host.reloadLibrary();
            host.toast("Renamed to " + newName);
        } catch (LibraryException e) {
            error(e.getMessage());
        }
    }

    // ---- delete ----------------------------------------------------------------------------------------------

    private boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(host.window());
        alert.setTitle(title);
        alert.setHeaderText(title);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    public void delete(Item item) {
        int used = ProjectLibrary.usage(host.timeline()).getOrDefault(item.name, 0);
        String message = used > 0
                ? "It is used by " + used + " clip" + (used == 1 ? "" : "s") + " on the timeline, which will be removed too. This can't be undone."
                : "This can't be undone.";
        if (!confirm("Delete " + item.name + "?", message)) return;
        deleteFiles(Collections.singletonList(item));
    }

    public void deleteUnused(List<Item> unused) {
        if (unused.isEmpty()) {
            host.toast("No unused files");
            return;
        }
        long bytes = 0;
        for (Item i : unused) bytes += i.bytes;
        if (!confirm("Delete " + unused.size() + " unused file" + (unused.size() == 1 ? "" : "s") + "?",
                "Frees " + readableSize(bytes) + ". No clip uses them. This can't be undone.")) return;
        deleteFiles(unused);
    }

    private void deleteFiles(List<Item> items) {
        List<String> names = new ArrayList<>();
        for (Item i : items) names.add(i.name);
        int removedClips = ProjectLibrary.removeClipsUsing(host.timeline(), names);
        for (String name : names) ProjectLibrary.delete(name, host.projectPath());
        host.clearHistory();
        host.timelineChanged(); // also drops the removed clips from the selection
        host.reloadLibrary();
        host.toast(names.size() == 1 ? "Deleted " + names.get(0) : "Deleted " + names.size() + " files");
    }

    public static String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(java.util.Locale.ROOT, v < 10 ? "%.1f %s" : "%.0f %s", v, units[u]);
    }

    // ---- replace ------------------------------------------------------------------------------------------------

    /** Every clip that uses the file switches to the new one, keeping its place; trims are clamped to the new length. */
    public void replace(Item old) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Replace " + old.name);
        File picked = chooser.showOpenDialog(host.window());
        if (picked == null) return;

        host.toast("Replacing...");
        new Thread(() -> {
            MediaHelper.MediaInfo info = MediaHelper.probeMediaInfo(picked.getAbsolutePath());
            Platform.runLater(() -> finishReplace(old, picked, info));
        }, "media-replace-probe").start();
    }

    private void finishReplace(Item old, File picked, MediaHelper.MediaInfo info) {
        Kind newKind = Kind.ofName(picked.getName());
        if (newKind != old.kind) {
            error("A " + old.kind.title.toLowerCase(java.util.Locale.ROOT) + " clip can only be replaced by another "
                    + old.kind.title.toLowerCase(java.util.Locale.ROOT) + " file.");
            return;
        }
        String newName;
        try {
            newName = ProjectLibrary.replace(old.name, picked, host.projectPath());
        } catch (LibraryException e) {
            error(e.getMessage());
            return;
        }
        ProjectLibrary.Probe probe = toProbe(newKind, info);
        int updated = 0, shortened = 0;
        for (Track track : host.timeline().tracks) {
            for (Clip clip : track.clips) {
                if (ProjectLibrary.usesFile(clip, old.name)) {
                    if (ProjectLibrary.repoint(clip, newName, probe)) shortened++;
                    updated++;
                }
            }
        }
        if (!newName.equals(old.name)) ProjectLibrary.delete(old.name, host.projectPath());
        host.timeline().recalculateDuration();
        host.clearHistory();

        final int updatedClips = updated, shortenedClips = shortened;
        File replacement = new File(ProjectLibrary.clipsDirectory(host.projectPath()), newName);
        Runnable finish = () -> {
            host.timelineChanged();
            host.reloadLibrary();
            String text = updatedClips == 0 ? "Replaced " + old.name
                    : "Replaced in " + updatedClips + " clip" + (updatedClips == 1 ? "" : "s");
            if (shortenedClips > 0) text += " \u00B7 " + shortenedClips + " shortened to fit";
            host.toast(text);
        };
        // The old proxies are stale (or gone): make new ones for the new file, then redraw.
        host.rebuildProxies(replacement, newKind.clipType(), info, finish);
    }

    // ---- solid colour -----------------------------------------------------------------------------------------------

    public void solidColor() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(host.window());
        dialog.setTitle("Solid colour");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ColorPicker picker = new ColorPicker(javafx.scene.paint.Color.web("#2D7DFF"));
        Label hint = new Label("Makes a 1920x1080 image in the project. Transparency is kept.");
        hint.setWrapText(true);
        VBox box = new VBox(10, picker, hint);
        box.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(box);
        if (!dialog.showAndWait().filter(b -> b == ButtonType.OK).isPresent()) return;
        javafx.scene.paint.Color c = picker.getValue();
        java.awt.Color color = new java.awt.Color((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue(), (float) c.getOpacity());
        try {
            String name = ProjectLibrary.createSolidColorImage(color, host.projectPath());
            host.reloadLibrary();
            host.toast("Created " + name);
        } catch (LibraryException e) {
            error(e.getMessage());
        }
    }
}
