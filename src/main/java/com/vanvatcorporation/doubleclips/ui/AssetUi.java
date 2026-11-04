package com.vanvatcorporation.doubleclips.ui;

import com.vanvatcorporation.doubleclips.drive.AssetKind;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.util.Locale;

/** Small shared bits of the Assets screens: the icon of a kind, readable sizes and counts. */
final class AssetUi {
    private AssetUi() {}

    static Ikon icon(AssetKind kind) {
        switch (kind) {
            case VIDEO: return MaterialDesignM.MOVIE;
            case IMAGE: return MaterialDesignI.IMAGE;
            case AUDIO: return MaterialDesignM.MUSIC_NOTE;
            case FONT: return MaterialDesignF.FORMAT_TEXT;
            case EFFECT: return MaterialDesignS.STAR;
            default: return MaterialDesignF.FOLDER;
        }
    }

    /** 1.4 MB, 820 KB, 312 B. */
    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, kb < 10 ? "%.1f KB" : "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, mb < 10 ? "%.1f MB" : "%.0f MB", mb);
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    /** 980, 1.2K, 3.4M. */
    static String count(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(Locale.ROOT, "%.1fK", n / 1000.0);
        return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
    }
}
