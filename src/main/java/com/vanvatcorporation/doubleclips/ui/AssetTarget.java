package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.AssetLibrary;
import com.vanvatcorporation.doubleclips.drive.AssetKind;
import javafx.stage.Window;

import java.io.File;

/**
 * Where downloaded media and fonts go when the Assets browser is opened from the editor: straight into the open project (no project
 * picker), as on iOS. The main window's Assets tab has no target: effects and packs are still installed, the rest is saved as a file.
 */
public interface AssetTarget {
    /** The editor window (the owner of the Assets window). */
    Window window();

    /** The open project's title, for the button ("Add to My Video"). */
    String projectTitle();

    /**
     * Adds a downloaded video / image / audio / font to the project. Called on the FX thread; may show its own progress.
     * @return a short line for the user ("Added to the Media tab")
     */
    String add(File file, AssetKind kind) throws AssetLibrary.InstallException;
}
