package com.vanvatcorporation.doubleclips.ui;

import com.google.gson.Gson;
import com.vanvatcorporation.doubleclips.data.AppSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The editor's dockable panels - media, preview, properties in the middle row and the timeline below - the way
 * CapCut does it.
 * <ul>
 * <li>Every panel has a grip bar on top. Drag it: a ghost of the panel follows the cursor and, over the place the
 *     panel docks (the middle row, or the bottom strip for the timeline), a blue zone shows where it would land.
 *     Release there to dock (or reorder) it.</li>
 * <li>Release anywhere else and the panel pops out into its own OS window - on this screen or another one - that
 *     you can move and resize freely. The other panels reflow to take the room.</li>
 * <li>A floating panel is dragged by its grip the same way: over its dock zone it shows the blue zone, release
 *     docks it. Closing its window, or double-clicking the grip, docks it back where it was. (Double-clicking a
 *     docked panel's grip pops it out.)</li>
 * <li>The layout - docked order, sizes, and every floating window's place and size - is remembered between
 *     sessions; {@link #resetAllToDefault()} (Settings) puts everything back.</li>
 * </ul>
 * One row panel is the "grower": it takes whatever width the others leave (the preview, or the first docked panel
 * when the preview is floating). Every other docked row panel has a resize strip on the edge facing the grower.
 */
public final class DockManager {
    private static final double MAX = Double.MAX_VALUE;
    private static final double DRAG_THRESHOLD = 6;
    private static final String HIGHLIGHT_STYLE = "-fx-background-color: rgba(74,95,255,0.28);"
            + " -fx-border-color: rgba(74,95,255,0.95); -fx-border-width: 2; -fx-background-radius: 8; -fx-border-radius: 8;";

    private static final List<DockManager> INSTANCES = new ArrayList<>();

    // ---- what is saved ---------------------------------------------------------------------------

    private static final class PanelState {
        double size;            // docked width (row panels) or height (the timeline)
        boolean floating;
        double x, y, w, h;      // a floating window: position and content size
        int index;              // where it docks back
    }

    private static final class State {
        List<String> order = new ArrayList<>();                 // docked row panels, left to right
        Map<String, PanelState> panels = new LinkedHashMap<>();
    }

    /** A panel plus its grip bar. Created by {@link #addPanel} / {@link #addBottomPanel}. */
    public static final class DockPanel extends VBox {
        final String title;
        final StackPane grip = new StackPane();
        final boolean bottom;               // the timeline slot instead of a middle-row slot
        final boolean grows;
        final double minSize, maxSize;      // docked width (row) or height (bottom) limits
        final double defaultSize, growMin;
        double dockedSize;
        boolean floating;
        boolean pendingFloat;               // a saved floating panel waiting for the window to be showing
        Stage stage;
        int lastIndex;
        // drag state
        double pressX, pressY, grabDX, grabDY;
        boolean dragging;

        DockPanel(String title, Region content, boolean bottom, double size, double minSize, double maxSize,
                  boolean grows, double growMin) {
            this.title = title;
            this.bottom = bottom;
            this.grows = grows;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.growMin = growMin;
            this.defaultSize = size;
            this.dockedSize = size;

            // The content used to carry its own size limits; the dock owns the size now.
            content.setMaxWidth(MAX);
            if (bottom) {
                content.setMinHeight(0);
                content.setPrefHeight(Region.USE_COMPUTED_SIZE);
                content.setMaxHeight(MAX);
            } else {
                content.setMinWidth(0);
                content.setPrefWidth(Region.USE_COMPUTED_SIZE);
            }
            VBox.setVgrow(content, Priority.ALWAYS);

            Label dots = new Label("\u2022\u2022\u2022");
            dots.setStyle("-fx-font-size: 10px; -fx-opacity: 0.45;");
            dots.setMouseTransparent(true);
            grip.getChildren().add(dots);
            grip.setAlignment(Pos.CENTER);
            grip.setMinHeight(14);
            grip.setPrefHeight(14);
            grip.setMaxHeight(14);
            grip.setCursor(Cursor.MOVE);
            grip.setStyle("-fx-background-color: transparent;");
            grip.setOnMouseEntered(e -> grip.setStyle("-fx-background-color: rgba(128,128,128,0.18);"));
            grip.setOnMouseExited(e -> grip.setStyle("-fx-background-color: transparent;"));
            Tooltip.install(grip, new Tooltip("Drag to move or pop out \u2022 double-click to float / dock"));

            getChildren().addAll(grip, content);
            if (bottom) setPrefHeight(size); else setPrefWidth(size);
        }
    }

    private final Stage mainStage;
    private final BorderPane mainContent;
    private final HBox row;
    private final StackPane overlayRoot;
    private final EventHandler<KeyEvent> keyFilter;
    private final List<DockPanel> docked = new ArrayList<>();   // docked row panels, left to right
    private final List<DockPanel> panels = new ArrayList<>();   // every panel, in creation (= default) order
    private DockPanel bottomPanel;
    private final Region highlight = new Region();
    private final PauseTransition saveDebounce = new PauseTransition(Duration.millis(500));
    private Popup ghost;
    private boolean dropOver;
    private int dropIndex = -1;
    private boolean closed;

    /**
     * @param mainContent the editor's BorderPane: the row is its center, the timeline its bottom
     * @param row         the editor's middle row the docked panels live in
     * @param overlayRoot the main window's root, where the blue drop zone is drawn
     * @param keyFilter   the editor's keyboard shortcuts, also installed on every floating window
     */
    public DockManager(Stage mainStage, BorderPane mainContent, HBox row, StackPane overlayRoot, EventHandler<KeyEvent> keyFilter) {
        this.mainStage = mainStage;
        this.mainContent = mainContent;
        this.row = row;
        this.overlayRoot = overlayRoot;
        this.keyFilter = keyFilter;
        highlight.setManaged(false);
        highlight.setMouseTransparent(true);
        highlight.setVisible(false);
        highlight.setStyle(HIGHLIGHT_STYLE);
        overlayRoot.getChildren().add(highlight);
        saveDebounce.setOnFinished(e -> saveLayout());
        INSTANCES.add(this);
    }

    /** Adds a middle-row panel, docked at the right end of the row (call {@link #start()} once all are added). */
    public DockPanel addPanel(String title, Region content, double width, double minW, double maxW, boolean grows, double growMin) {
        DockPanel p = new DockPanel(title, content, false, width, minW, maxW, grows, growMin);
        installGrip(p);
        panels.add(p);
        docked.add(p);
        return p;
    }

    /** Adds the timeline slot (the editor's bottom strip). */
    public DockPanel addBottomPanel(String title, Region content, double height, double minH, double maxH) {
        DockPanel p = new DockPanel(title, content, true, height, minH, maxH, false, 0);
        installGrip(p);
        panels.add(p);
        bottomPanel = p;
        return p;
    }

    /** Applies the remembered layout (if any) and builds the editor's layout. */
    public void start() {
        restoreSavedLayout();
        layout();
    }

    // ---- layout -------------------------------------------------------------------------------

    /** Rebuilds the middle row and the timeline slot from the docked panels. */
    public void layout() {
        captureSizes();
        layoutRow();
        layoutBottom();
    }

    /** Remembers the size each docked panel has right now, so a rebuild keeps what the user dragged them to. */
    private void captureSizes() {
        for (DockPanel p : panels) {
            if (p.floating || p.pendingFloat || p.getScene() == null) continue;
            double size = p.bottom ? p.getHeight() : p.getWidth();
            if (size > 50) p.dockedSize = size;
        }
    }

    private void layoutRow() {
        row.getChildren().clear();
        DockPanel grower = null;
        for (DockPanel p : docked) if (p.grows) grower = p;
        if (grower == null && !docked.isEmpty()) grower = docked.get(0);
        final DockPanel growerFinal = grower;
        int gi = docked.indexOf(grower);

        for (int i = 0; i < docked.size(); i++) {
            DockPanel p = docked.get(i);
            p.setMinHeight(Region.USE_COMPUTED_SIZE);
            p.setPrefHeight(Region.USE_COMPUTED_SIZE);
            p.setMaxHeight(MAX);
            if (p == growerFinal) {
                HBox.setHgrow(p, Priority.ALWAYS);
                p.setMinWidth(120);
                p.setMaxWidth(MAX);
            } else {
                HBox.setHgrow(p, Priority.NEVER);
                p.setMinWidth(p.minSize);
                p.setMaxWidth(p.maxSize);
            }
            p.setPrefWidth(p.dockedSize);
            row.getChildren().add(p);

            if (i < docked.size() - 1) {
                // The strip between two panels resizes whichever of them is not on the grower's side of it.
                DockPanel next = docked.get(i + 1);
                final DockPanel target = i < gi ? p : next;
                row.getChildren().add(PanelResizeHandle.horizontal(target, target == p, target.minSize, target.maxSize,
                        () -> limitFor(target, growerFinal)).onFinished(this::requestSave));
            }
        }
    }

    private void layoutBottom() {
        if (bottomPanel == null) return;
        clearBottomWrapper();
        DockPanel p = bottomPanel;
        if (p.floating || p.pendingFloat) {
            mainContent.setBottom(null);
            return;
        }
        p.setMinWidth(0);
        p.setPrefWidth(Region.USE_COMPUTED_SIZE);
        p.setMaxWidth(MAX);
        p.setPrefHeight(p.dockedSize);
        PanelResizeHandle h = PanelResizeHandle.verticalAbove(p, p.minSize, p.maxSize,
                () -> mainContent.getHeight() - 280 - PanelResizeHandle.THICKNESS).onFinished(this::requestSave);
        mainContent.setBottom(new VBox(h, p));
    }

    /** The timeline sits in a small wrapper (strip + panel); it must let go of the panel before the panel moves. */
    private void clearBottomWrapper() {
        Node b = mainContent.getBottom();
        if (b instanceof Pane pane) pane.getChildren().clear();
    }

    /** The widest a row panel may get right now: the others (and the grower's minimum) still need their room. */
    private double limitFor(DockPanel target, DockPanel grower) {
        double others = 0;
        for (DockPanel q : docked) if (q != target && q != grower) others += q.getWidth();
        double handles = Math.max(0, docked.size() - 1) * PanelResizeHandle.THICKNESS;
        double growerRoom = grower == null ? 0 : grower.growMin;
        return row.getWidth() - others - handles - growerRoom;
    }

    // ---- docking / floating --------------------------------------------------------------------

    /** Pops the panel out into its own OS window with its content area at (x, y), w x h. */
    private void floatPanel(DockPanel p, double x, double y, double w, double h, boolean rememberSize) {
        if (p.floating) return;
        if (!p.bottom && docked.contains(p)) p.lastIndex = docked.indexOf(p);
        if (rememberSize) p.dockedSize = p.bottom ? h : w;

        if (p.bottom) {
            p.pendingFloat = true;
        } else {
            docked.remove(p);
        }
        layout(); // lets the panel go (out of the row / the timeline wrapper)

        // A panel keeps the x/y the row gave it; as a window's root that would shift it right of the window's edge.
        p.setLayoutX(0);
        p.setLayoutY(0);
        p.setTranslateX(0);
        p.setTranslateY(0);
        p.setMinSize(p.bottom ? 320 : 180, 120);
        p.setMaxSize(MAX, MAX);
        p.setPrefSize(w, h);
        if (!p.getStyleClass().contains("editor-root")) p.getStyleClass().add("editor-root");

        Stage st = new Stage(StageStyle.UTILITY);
        st.initOwner(mainStage);
        st.setTitle(p.title);
        Scene sc = new Scene(p, w, h);
        if (mainStage.getScene() != null) sc.getStylesheets().addAll(mainStage.getScene().getStylesheets());
        sc.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
        st.setScene(sc);
        st.setX(x);
        st.setY(y);
        st.setOnCloseRequest(e -> { e.consume(); dock(p, p.lastIndex); });
        ChangeListener<Number> geometry = (o, a, b) -> requestSave();
        st.xProperty().addListener(geometry);
        st.yProperty().addListener(geometry);
        sc.widthProperty().addListener(geometry);
        sc.heightProperty().addListener(geometry);
        p.pendingFloat = false;
        p.floating = true;
        p.stage = st;
        st.show();
        saveLayout();
    }

    /** Lets a floating panel go: its window is hidden and the panel is free to be put somewhere else. */
    private void releaseFloating(DockPanel p) {
        Stage st = p.stage;
        p.floating = false;
        p.pendingFloat = false;
        p.stage = null;
        if (st != null) {
            st.setOnCloseRequest(null);
            if (st.getScene() != null) st.getScene().setRoot(new Region()); // frees the panel from the window's scene
            st.hide();
        }
        p.getStyleClass().remove("editor-root");
    }

    /** Docks the panel at {@code index} in the row (also used to reorder an already docked panel). */
    private void dock(DockPanel p, int index) {
        if (p.floating) releaseFloating(p);
        else if (!p.bottom) docked.remove(p);
        if (!p.bottom) docked.add(Math.max(0, Math.min(index, docked.size())), p);
        layout();
        saveLayout();
    }

    /** Closes the floating windows (the editor is closing); their panels die with it. The layout is saved first. */
    public void shutdown() {
        if (closed) return;
        saveLayout();
        closed = true;
        for (DockPanel p : panels) if (p.floating) releaseFloating(p);
        hideHighlight();
        hideGhost();
        INSTANCES.remove(this);
    }

    // ---- remembering the layout --------------------------------------------------------------------

    private void requestSave() {
        saveDebounce.playFromStart();
    }

    private void saveLayout() {
        if (closed) return;
        try {
            captureSizes();
            State st = new State();
            for (DockPanel p : docked) st.order.add(p.title);
            for (DockPanel p : panels) {
                PanelState s = new PanelState();
                s.size = p.dockedSize;
                s.floating = p.floating;
                s.index = p.bottom ? 0 : (docked.contains(p) ? docked.indexOf(p) : p.lastIndex);
                if (p.floating && p.stage != null && p.stage.getScene() != null) {
                    s.x = p.stage.getX();
                    s.y = p.stage.getY();
                    s.w = p.stage.getScene().getWidth();
                    s.h = p.stage.getScene().getHeight();
                }
                st.panels.put(p.title, s);
            }
            AppSettings.getInstance().setEditorLayout(new Gson().toJson(st));
        } catch (Exception ignored) {
            // A layout that can't be saved is only a missed convenience.
        }
    }

    private DockPanel byTitle(String title) {
        for (DockPanel p : panels) if (p.title.equals(title)) return p;
        return null;
    }

    private void restoreSavedLayout() {
        String json = AppSettings.getInstance().getEditorLayout();
        if (json == null || json.isBlank()) return;
        State st;
        try {
            st = new Gson().fromJson(json, State.class);
        } catch (Exception e) {
            return;
        }
        if (st == null || st.panels == null) return;
        final State saved = st;

        List<DockPanel> floats = new ArrayList<>();
        for (DockPanel p : panels) {
            PanelState s = st.panels.get(p.title);
            if (s == null) continue;
            if (s.size >= p.minSize && s.size <= 4000) p.dockedSize = Math.min(s.size, Math.max(p.maxSize, p.dockedSize));
            p.lastIndex = Math.max(0, s.index);
            if (s.floating) {
                p.pendingFloat = true;
                floats.add(p);
            }
        }

        // Docked row panels in the saved order; any panel the save doesn't know keeps its default place at the end.
        List<DockPanel> order = new ArrayList<>();
        if (st.order != null) {
            for (String title : st.order) {
                DockPanel p = byTitle(title);
                if (p != null && !p.bottom && !p.pendingFloat && !order.contains(p)) order.add(p);
            }
        }
        for (DockPanel p : panels) if (!p.bottom && !p.pendingFloat && !order.contains(p)) order.add(p);
        docked.clear();
        docked.addAll(order);

        if (floats.isEmpty()) return;
        Runnable openFloats = () -> {
            for (DockPanel p : floats) {
                PanelState s = saved.panels.get(p.title);
                double w = Math.max(240, s.w), h = Math.max(160, s.h);
                double x = s.x, y = s.y;
                // A monitor that is gone since the layout was saved: bring the window back onto the main one.
                if (Screen.getScreensForRectangle(x, y, w, h).isEmpty()) {
                    x = mainStage.getX() + 60;
                    y = mainStage.getY() + 80;
                }
                floatPanel(p, x, y, w, h, false);
            }
        };
        if (mainStage.isShowing()) {
            Platform.runLater(openFloats);
        } else {
            mainStage.showingProperty().addListener(new ChangeListener<Boolean>() {
                @Override public void changed(javafx.beans.value.ObservableValue<? extends Boolean> o, Boolean was, Boolean now) {
                    if (now) {
                        mainStage.showingProperty().removeListener(this);
                        Platform.runLater(openFloats);
                    }
                }
            });
        }
    }

    /** Settings > "Restore default layout": forgets the saved layout and puts every open editor back to the default. */
    public static void resetAllToDefault() {
        AppSettings.getInstance().setEditorLayout("");
        for (DockManager m : new ArrayList<>(INSTANCES)) m.resetToDefault();
    }

    private void resetToDefault() {
        for (DockPanel p : panels) {
            if (p.floating) releaseFloating(p);
            p.pendingFloat = false;
            p.dockedSize = p.defaultSize;
        }
        docked.clear();
        for (DockPanel p : panels) if (!p.bottom) docked.add(p);
        row.getChildren().clear();
        layout();
        saveLayout();
    }

    // ---- dragging a grip -----------------------------------------------------------------------

    private void installGrip(DockPanel p) {
        p.grip.setOnMousePressed(e -> {
            p.pressX = e.getScreenX();
            p.pressY = e.getScreenY();
            p.dragging = false;
            if (p.floating && p.stage != null) {
                p.grabDX = e.getScreenX() - p.stage.getX();
                p.grabDY = e.getScreenY() - p.stage.getY();
            } else {
                Bounds b = p.localToScreen(p.getBoundsInLocal());
                p.grabDX = b == null ? 20 : e.getScreenX() - b.getMinX();
                p.grabDY = b == null ? 7 : e.getScreenY() - b.getMinY();
            }
            e.consume();
        });

        p.grip.setOnMouseDragged(e -> {
            if (!p.dragging) {
                if (Math.hypot(e.getScreenX() - p.pressX, e.getScreenY() - p.pressY) < DRAG_THRESHOLD) return;
                p.dragging = true;
                if (!p.floating) showGhost(p);
            }
            if (p.floating && p.stage != null) {
                p.stage.setX(e.getScreenX() - p.grabDX);
                p.stage.setY(e.getScreenY() - p.grabDY);
            } else {
                moveGhost(e.getScreenX(), e.getScreenY());
            }
            updateDropTarget(p, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        p.grip.setOnMouseReleased(e -> {
            if (!p.dragging) return;
            p.dragging = false;
            finishDrag(p, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        p.grip.setOnMouseClicked(e -> {
            if (e.getClickCount() != 2 || !e.isStillSincePress()) return;
            if (p.floating) {
                dock(p, p.lastIndex);
            } else {
                Bounds b = p.localToScreen(p.getBoundsInLocal());
                if (b != null) floatPanel(p, b.getMinX() + 36, b.getMinY() + 36, b.getWidth(), b.getHeight(), true);
            }
            e.consume();
        });
    }

    private void finishDrag(DockPanel p, double sx, double sy) {
        boolean over = dropOver;
        int target = dropIndex;
        hideHighlight();
        if (!p.floating) {
            // Where the pop-out window will go, and how big - read before the panel leaves its slot.
            double w = p.getWidth(), h = p.getHeight();
            double gx = ghost != null ? ghost.getAnchorX() : sx - p.grabDX;
            double gy = ghost != null ? ghost.getAnchorY() : sy - p.grabDY;
            hideGhost();
            if (over) {
                if (!p.bottom) dock(p, target);   // the timeline is already in its slot
            } else {
                floatPanel(p, gx, gy, w, h, true);
            }
        } else if (over) {
            dock(p, p.bottom ? 0 : target);
        }
        // a floating panel released outside its dock zone simply stays where it was dropped
    }

    // ---- the ghost (a faded picture of a docked panel being dragged) ------------------------------

    private void showGhost(DockPanel p) {
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        WritableImage img = p.snapshot(sp, null);
        ImageView iv = new ImageView(img);
        iv.setPreserveRatio(true);
        iv.setFitWidth(Math.min(img.getWidth(), 420));
        iv.setOpacity(0.8);
        iv.setMouseTransparent(true);
        iv.setEffect(new DropShadow(18, Color.rgb(0, 0, 0, 0.45)));
        ghost = new Popup();
        ghost.setAutoFix(false);
        ghost.setAutoHide(false);
        ghost.setHideOnEscape(false);
        ghost.getContent().add(iv);
        ghost.show(mainStage, p.pressX - iv.getFitWidth() / 2, p.pressY - 8);
    }

    private void moveGhost(double sx, double sy) {
        if (ghost == null || ghost.getContent().isEmpty()) return;
        double w = ((ImageView) ghost.getContent().get(0)).getFitWidth();
        ghost.setAnchorX(sx - w / 2);
        ghost.setAnchorY(sy - 8);
    }

    private void hideGhost() {
        if (ghost != null) { ghost.hide(); ghost = null; }
    }

    // ---- the blue drop zone ------------------------------------------------------------------------

    /**
     * Works out where the panel would dock and lights it up: a middle-row panel over the row (the slot among the
     * other docked panels), the timeline over the lower part of the editor (its bottom strip).
     */
    private void updateDropTarget(DockPanel p, double sx, double sy) {
        if (p.bottom) {
            updateBottomDropTarget(p, sx, sy);
            return;
        }
        Bounds rb = row.localToScreen(row.getBoundsInLocal());
        if (rb == null || !rb.contains(sx, sy)) {
            hideHighlight();
            return;
        }
        List<DockPanel> others = new ArrayList<>(docked);
        others.remove(p);
        int idx = 0;
        for (DockPanel o : others) {
            Bounds ob = o.localToScreen(o.getBoundsInLocal());
            if (ob != null && sx > (ob.getMinX() + ob.getMaxX()) / 2) idx++;
        }
        dropOver = true;
        dropIndex = idx;

        double w = Math.max(240, Math.min(p.floating ? p.getWidth() : p.dockedSize, 420));
        double x;
        if (others.isEmpty()) {
            x = rb.getMinX();
            w = rb.getWidth();
        } else if (idx == 0) {
            x = rb.getMinX();
        } else if (idx >= others.size()) {
            x = rb.getMaxX() - w;
        } else {
            Bounds nb = others.get(idx).localToScreen(others.get(idx).getBoundsInLocal());
            x = nb != null ? nb.getMinX() : rb.getMinX();
        }
        showHighlight(x, rb.getMinY(), w, rb.getHeight());
    }

    private void updateBottomDropTarget(DockPanel p, double sx, double sy) {
        Bounds mb = mainContent.localToScreen(mainContent.getBoundsInLocal());
        if (mb == null) { hideHighlight(); return; }
        double zoneH = Math.max(200, Math.min(p.dockedSize, mb.getHeight() * 0.5));
        double top = mb.getMaxY() - zoneH;
        if (sx < mb.getMinX() || sx > mb.getMaxX() || sy < top || sy > mb.getMaxY()) {
            hideHighlight();
            return;
        }
        dropOver = true;
        dropIndex = 0;
        showHighlight(mb.getMinX(), top, mb.getWidth(), zoneH);
    }

    private void showHighlight(double screenX, double screenY, double w, double h) {
        Point2D local = overlayRoot.screenToLocal(screenX, screenY);
        if (local == null) return;
        highlight.resizeRelocate(local.getX(), local.getY(), w, h);
        highlight.setVisible(true);
    }

    private void hideHighlight() {
        highlight.setVisible(false);
        dropOver = false;
        dropIndex = -1;
    }
}
