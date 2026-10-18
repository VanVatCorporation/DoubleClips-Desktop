package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.data.editing.Clip;
import com.vanvatcorporation.doubleclips.data.editing.Timeline;
import com.vanvatcorporation.doubleclips.data.editing.Track;
import com.vanvatcorporation.doubleclips.history.CloseTrackGapsCommand;
import com.vanvatcorporation.doubleclips.history.DeleteTrackCommand;
import com.vanvatcorporation.doubleclips.history.DuplicateTrackCommand;
import com.vanvatcorporation.doubleclips.history.HistoryManager;
import com.vanvatcorporation.doubleclips.history.MoveTrackCommand;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import java.util.ArrayList;
import java.util.List;

/**
 * What can be done to a whole track once it is selected (click its header; right-click the header or an empty part of
 * its row): the menu and the actions behind it. The mobile apps have the same set on their track toolbar
 * (select all, delete, split, auto snap ...); desktop adds copy / paste / duplicate / move.
 * <p>
 * Every change that edits the project is one undo step. Copy, paste and split are the editor's own commands (they act
 * on the selected track), so they are reached through {@link Host}.
 */
final class TrackActions {

    interface Host {
        Timeline timeline();
        HistoryManager history();
        float playhead();
        /** Makes this the selected track (clears any clip selection); null clears the track selection. */
        void selectTrack(Track track);
        void selectClips(List<Clip> clips);
        /** After an edit: track headers, timeline, project end, autosave. */
        void afterEdit();
        void copyTrack();
        void pasteHere();
        void splitHere();
    }

    private final Host host;

    TrackActions(Host host) {
        this.host = host;
    }

    /** The menu for one track. The caller selects the track first so Copy / Paste / Split act on it. */
    ContextMenu menuFor(Track track) {
        Timeline tl = host.timeline();
        int index = tl.tracks.indexOf(track);
        boolean empty = track.clips.isEmpty();
        float t = host.playhead();
        boolean playheadInside = false;
        for (Clip c : track.clips) if (t > c.startTime && t < c.startTime + c.duration) { playheadInside = true; break; }

        MenuItem selectAll = item("Select all clips in track", empty, () -> selectAllClips(track));
        MenuItem split = item("Split at playhead", !playheadInside, host::splitHere);
        MenuItem copy = item("Copy track", false, host::copyTrack);
        MenuItem paste = item("Paste here", false, host::pasteHere);
        MenuItem duplicate = item("Duplicate track", false, () -> duplicate(track));
        MenuItem closeGaps = item("Close gaps", track.clips.size() < 2, () -> closeGaps(track));
        MenuItem up = item("Move up", index <= 0, () -> move(track, -1));
        MenuItem down = item("Move down", index < 0 || index >= tl.tracks.size() - 1, () -> move(track, +1));
        MenuItem delete = item("Delete track", false, () -> delete(track));

        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(selectAll, split, closeGaps,
                new SeparatorMenuItem(), copy, paste, duplicate,
                new SeparatorMenuItem(), up, down,
                new SeparatorMenuItem(), delete);
        return menu;
    }

    private static MenuItem item(String text, boolean disabled, Runnable action) {
        MenuItem m = new MenuItem(text);
        m.setDisable(disabled);
        m.setOnAction(e -> action.run());
        return m;
    }

    // ---- the actions --------------------------------------------------------------------------------

    void selectAllClips(Track track) {
        if (track.clips.isEmpty()) return;
        List<Clip> sorted = new ArrayList<>(track.clips);
        sorted.sort((a, b) -> Float.compare(a.startTime, b.startTime));
        host.selectClips(sorted);
    }

    /** The copy goes right below the original and becomes the selected track. */
    void duplicate(Track track) {
        DuplicateTrackCommand cmd = new DuplicateTrackCommand(host.timeline(), track, host::afterEdit);
        host.history().execute(cmd);
        host.selectTrack(cmd.getCopy());
    }

    void delete(Track track) {
        host.history().execute(new DeleteTrackCommand(host.timeline(), track, () -> {
            host.selectTrack(null);
            host.afterEdit();
        }));
    }

    void closeGaps(Track track) {
        CloseTrackGapsCommand cmd = new CloseTrackGapsCommand(host.timeline(), track, host::afterEdit);
        if (cmd.hasChanges()) host.history().execute(cmd);     // nothing to close: no empty undo step
    }

    /** delta -1: one place up the list (drawn below its neighbour); +1: one place down (drawn above it). */
    void move(Track track, int delta) {
        Timeline tl = host.timeline();
        int from = tl.tracks.indexOf(track);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= tl.tracks.size()) return;
        host.history().execute(new MoveTrackCommand(tl, from, to, host::afterEdit));
    }
}
