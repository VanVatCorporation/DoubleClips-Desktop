package com.vanvatcorporation.doubleclips.ui;

import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's dockable panels (media, preview, properties), the way CapCut does it.
 * <ul>
 * <li>Every panel has a grip bar on top. Drag it: a ghost of the panel follows the cursor and, over the editor's
 *     middle row, a blue zone shows where the panel would dock. Release there to dock (reorder) it.</li>
 * <li>Release anywhere else and the panel pops out into its own OS window - on this screen or another one - that
 *     you can move and resize freely. The other panels reflow to take the room.</li>
 * <li>A floating panel is dragged by its grip the same way: over the row it shows the blue zone, release docks it.
 *     Closing its window, or double-clicking the grip, docks it back where it was. (Double-clicking a docked
 *     panel's grip pops it out.)</li>
 * </ul>
 * One panel is the "grower": it takes whatever width the others leave (the preview, or the first docked panel
 * when the preview is floating). Every other docked panel has a resize strip on the edge facing the grower.
 */
public final class DockManager {
    private static final double MAX = Double.MAX_VALUE;
    private static final double DRAG_THRESHOLD = 6;
    private static final String HIGHLIGHT_STYLE = "-fx-background-color: rgba(74,95,255,0.28);"
            + " -fx-border-color: rgba(74,95,255,0.95); -fx-border-width: 2; -fx-background-radius: 8; -fx-border-radius: 8;";

    /** A panel plus its grip bar. Created by {@link #addPanel}. */
    public static final class DockPanel extends VBox {
        final String title;
        final StackPane grip = new StackPane();
        final boolean grows;
        final double minW, maxW, growMin;
        double dockedWidth;
        boolean floating;
        Stage stage;
        int lastIndex;
        // drag state
        double pressX, pressY, grabDX, grabDY;
        boolean dragging;

        DockPanel(String title, Region content, double width, double minW, double maxW, boolean grows, double growMin) {
            this.title = title;
            this.grows = grows;
            this.minW = minW;
            this.maxW = maxW;
            this.growMin = growMin;
            this.dockedWidth = width;

            // The content used to carry its own width limits; the dock now owns the width.
            content.setMinWidth(0);
            content.setPrefWidth(Region.USE_COMPUTED_SIZE);
            content.setMaxWidth(MAX);
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
            setPrefWidth(width);
        }
    }

    private final Stage mainStage;
    private final HBox row;
    private final StackPane overlayRoot;
    private final EventHandler<KeyEvent> keyFilter;
    private final List<DockPanel> docked = new ArrayList<>();
    private final List<DockPanel> panels = new ArrayList<>();
    private final Region highlight = new Region();
    private Popup ghost;
    private int dropIndex = -1;

    /**
     * @param row         the editor's middle row the docked panels live in
     * @param overlayRoot the main window's root, where the blue drop zone is drawn
     * @param keyFilter   the editor's keyboard shortcuts, also installed on every floating window
     */
    public DockManager(Stage mainStage, HBox row, StackPane overlayRoot, EventHandler<KeyEvent> keyFilter) {
        this.mainStage = mainStage;
        this.row = row;
        this.overlayRoot = overlayRoot;
        this.keyFilter = keyFilter;
        highlight.setManaged(false);
        highlight.setMouseTransparent(true);
        highlight.setVisible(false);
        highlight.setStyle(HIGHLIGHT_STYLE);
        overlayRoot.getChildren().add(highlight);
    }

    /** Adds a panel, docked at the right end of the row (call {@link #layout()} once all are added). */
    public DockPanel addPanel(String title, Region content, double width, double minW, double maxW, boolean grows, double growMin) {
        DockPanel p = new DockPanel(title, content, width, minW, maxW, grows, growMin);
        installGrip(p);
        panels.add(p);
        docked.add(p);
        return p;
    }

    // ---- layout -------------------------------------------------------------------------------

    /** Rebuilds the row from the docked panels: the grower fills, the others keep their width and get a resize strip. */
    public void layout() {
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
                p.setMinWidth(p.minW);
                p.setMaxWidth(p.maxW);
            }
            p.setPrefWidth(p.dockedWidth);
            row.getChildren().add(p);

            if (i < docked.size() - 1) {
                // The strip between two panels resizes whichever of them is not on the grower's side of it.
                DockPanel next = docked.get(i + 1);
                final DockPanel target = i < gi ? p : next;
                row.getChildren().add(PanelResizeHandle.horizontal(target, target == p, target.minW, target.maxW,
                        () -> limitFor(target, growerFinal)));
            }
        }
    }

    /** The widest a panel may get right now: the others (and the grower's minimum) still need their room. */
    private double limitFor(DockPanel target, DockPanel grower) {
        double others = 0;
        for (DockPanel q : docked) if (q != target && q != grower) others += q.getWidth();
        double handles = Math.max(0, docked.size() - 1) * PanelResizeHandle.THICKNESS;
        double growerRoom = grower == null ? 0 : grower.growMin;
        return row.getWidth() - others - handles - growerRoom;
    }

    // ---- docking / floating --------------------------------------------------------------------

    /** Pops the panel out into its own OS window with its content area at (x, y), w x h. */
    private void floatPanel(DockPanel p, double x, double y, double w, double h) {
        if (p.floating) return;
        p.lastIndex = Math.max(0, docked.indexOf(p));
        if (w > 50) p.dockedWidth = w;
        docked.remove(p);
        layout(); // detaches p from the row

        p.setMinSize(180, 120);
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
        p.floating = true;
        p.stage = st;
        st.show();
    }

    /** Docks the panel at {@code index} in the row (also used to reorder an already docked panel). */
    private void dock(DockPanel p, int index) {
        if (p.floating) {
            Stage st = p.stage;
            p.floating = false;
            p.stage = null;
            st.setOnCloseRequest(null);
            st.getScene().setRoot(new Region()); // free the panel from the floating scene before it joins the row
            st.hide();
            p.getStyleClass().remove("editor-root");
        } else {
            docked.remove(p);
        }
        docked.add(Math.max(0, Math.min(index, docked.size())), p);
        layout();
    }

    /** Closes the floating windows (the editor is closing); their panels die with it. */
    public void shutdown() {
        for (DockPanel p : panels) {
            if (p.floating && p.stage != null) {
                p.stage.setOnCloseRequest(null);
                p.stage.getScene().setRoot(new Region());
                p.stage.hide();
                p.floating = false;
                p.stage = null;
            }
        }
        hideHighlight();
        hideGhost();
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
                moveGhost(p, e.getScreenX(), e.getScreenY());
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
                if (b != null) floatPanel(p, b.getMinX() + 36, b.getMinY() + 36, b.getWidth(), b.getHeight());
            }
            e.consume();
        });
    }

    private void finishDrag(DockPanel p, double sx, double sy) {
        int target = dropIndex;
        hideHighlight();
        if (!p.floating) {
            // Where the pop-out window will go, and how big - read before the panel leaves the row.
            double w = p.getWidth(), h = p.getHeight();
            double gx = ghost != null ? ghost.getAnchorX() : sx - p.grabDX;
            double gy = ghost != null ? ghost.getAnchorY() : sy - p.grabDY;
            hideGhost();
            if (target >= 0) dock(p, target);
            else floatPanel(p, gx, gy, w, h);
        } else if (target >= 0) {
            dock(p, target);
        }
        // a floating panel released outside the row simply stays where it was dropped
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

    private void moveGhost(DockPanel p, double sx, double sy) {
        if (ghost == null || ghost.getContent().isEmpty()) return;
        double w = ((ImageView) ghost.getContent().get(0)).getFitWidth();
        ghost.setAnchorX(sx - w / 2);
        ghost.setAnchorY(sy - 8);
    }

    private void hideGhost() {
        if (ghost != null) { ghost.hide(); ghost = null; }
    }

    // ---- the blue drop zone ------------------------------------------------------------------------

    /** Over the row: works out where the panel would dock (the slot among the other docked panels) and lights it up. */
    private void updateDropTarget(DockPanel p, double sx, double sy) {
        Bounds rb = row.localToScreen(row.getBoundsInLocal());
        if (rb == null || !rb.contains(sx, sy)) {
            dropIndex = -1;
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
        dropIndex = idx;

        double w = Math.max(240, Math.min(p.floating ? p.getWidth() : p.dockedWidth, 420));
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
        Point2D local = overlayRoot.screenToLocal(x, rb.getMinY());
        if (local == null) return;
        highlight.resizeRelocate(local.getX(), local.getY(), w, rb.getHeight());
        highlight.setVisible(true);
    }

    private void hideHighlight() {
        highlight.setVisible(false);
        dropIndex = -1;
    }
}
