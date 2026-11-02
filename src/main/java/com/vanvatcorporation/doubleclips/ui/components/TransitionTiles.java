package com.vanvatcorporation.doubleclips.ui.components;

import com.vanvatcorporation.doubleclips.FXCommandEmitter;
import com.vanvatcorporation.doubleclips.OpenGLEdit;
import com.vanvatcorporation.doubleclips.TransitionStyle;
import com.vanvatcorporation.doubleclips.TransitionStyleCatalog;
import com.vanvatcorporation.doubleclips.TransitionStyleLoader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The transition styles as tiles, shared by every place that shows them: the picker window opened from a transition's properties
 * and the editor's left Transitions pane (where a tile is dragged onto the knot between two clips). Each tile is the style playing
 * between two pictures, drawn by the preview worker's real renderer.
 */
public final class TransitionTiles {

    private TransitionTiles() {}

    /**
     * Every style the project can use: "none", the ones both engines draw, the data-driven (OpenGL-only) ones, then the FFmpeg-only
     * ones - each badged. {@code currentStyle} (may be null) that is in none of the lists stays selectable as "[not installed]", so
     * opening a picker and closing it again never overwrites it.
     */
    public static List<TransitionStyleCatalog.Entry> entries(String currentStyle) {
        return TransitionStyleCatalog.build(
                FXCommandEmitter.FXRegistry.transitionFXMap,
                OpenGLEdit.SUPPORTED_TRANSITION_STYLES,
                TransitionStyleLoader.list(),
                true,
                currentStyle);
    }

    /** The entry's label without the "[FFmpeg only ...]" / "[OpenGL only ...]" suffix (the tile's badges say it already). */
    public static String plainTitle(TransitionStyleCatalog.Entry entry) {
        return entry.label.replaceAll("\\s*\\[(FFmpeg only|OpenGL only)[^\\]]*\\]$", "");
    }

    /** The tiles grouped by the engines that draw them: "Both engines", "OpenGL only", "FFmpeg only", "Not installed". */
    public static List<TileGridDialog.Section> sections(List<TransitionStyleCatalog.Entry> entries,
                                                        Function<TransitionStyleCatalog.Entry, String> plainTitle,
                                                        GpuTilePreviews previews) {
        Map<TransitionStyleCatalog.Engine, List<TileGridDialog.Tile>> groups = new EnumMap<>(TransitionStyleCatalog.Engine.class);
        for (TransitionStyleCatalog.Entry entry : entries) {
            Set<String> engines = new LinkedHashSet<>();
            switch (entry.engine) {
                case BOTH:
                    engines.add(TileGridDialog.ENGINE_FFMPEG);
                    engines.add(TileGridDialog.ENGINE_OPENGL);
                    break;
                case OPENGL_ONLY:
                    engines.add(TileGridDialog.ENGINE_OPENGL);
                    break;
                case FFMPEG_ONLY:
                    engines.add(TileGridDialog.ENGINE_FFMPEG);
                    break;
                default:
                    break;
            }
            String author = null;
            TransitionStyle dataStyle = entry.dataDriven ? TransitionStyleLoader.get(entry.id) : null;
            if (dataStyle != null && dataStyle.getAuthor() != null && !dataStyle.getAuthor().isBlank()) author = dataStyle.getAuthor();
            // Only a style the OpenGL renderer draws has a picture to play; the others stay still tiles.
            boolean drawn = entry.engine == TransitionStyleCatalog.Engine.BOTH || entry.engine == TransitionStyleCatalog.Engine.OPENGL_ONLY;
            final String id = entry.id;
            groups.computeIfAbsent(entry.engine, k -> new ArrayList<>()).add(
                    new TileGridDialog.Tile(id, plainTitle.apply(entry), author, engines,
                            () -> previews.transitionStill(id), false,
                            drawn && !"none".equals(id) ? () -> previews.transitionLoop(id) : null));
        }
        List<TileGridDialog.Section> sections = new ArrayList<>();
        String[] titles = {"Both engines", "OpenGL only", "FFmpeg only", "Not installed"};
        TransitionStyleCatalog.Engine[] order = {
                TransitionStyleCatalog.Engine.BOTH,
                TransitionStyleCatalog.Engine.OPENGL_ONLY,
                TransitionStyleCatalog.Engine.FFMPEG_ONLY,
                TransitionStyleCatalog.Engine.UNKNOWN};
        for (int i = 0; i < order.length; i++) {
            List<TileGridDialog.Tile> tiles = groups.get(order[i]);
            if (tiles != null) sections.add(new TileGridDialog.Section(titles[i], tiles));
        }
        return sections;
    }
}
