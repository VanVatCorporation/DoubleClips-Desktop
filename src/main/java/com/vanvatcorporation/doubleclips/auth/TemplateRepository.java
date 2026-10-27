package com.vanvatcorporation.doubleclips.auth;

import com.google.gson.JsonObject;
import com.vanvatcorporation.doubleclips.data.TemplateData;
import javafx.application.Platform;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The Templates backend (app.vanvatcorp.com, backend v2), the only place the desktop app talks to it.
 *
 * ONE state cache: every like / bookmark / count the app learns (from a page, a like, a view...) lands in {@link #states},
 * and every screen reads it back through {@link #applyCached}. That is what keeps the grid, the preview and the Profile
 * lists showing the same numbers; {@link #addStateListener} repaints a screen that sits underneath another one.
 *
 * Everything public is called on, and calls back on, the JavaFX thread.
 */
public class TemplateRepository {

    public static final int PAGE_SIZE = 20;

    private static TemplateRepository instance;
    private final TemplateApiService api;

    /** What the server last said about one template, for this viewer. */
    public static final class State {
        public boolean isLiked, isBookmarked;
        public int viewCount, useCount, heartCount, bookmarkCount, commentCount;

        static State of(TemplateData d) {
            State s = new State();
            s.isLiked = d.isLiked;
            s.isBookmarked = d.isBookmarked;
            s.viewCount = d.getViewCount();
            s.useCount = d.getUseCount();
            s.heartCount = d.getHeartCount();
            s.bookmarkCount = d.getBookmarkCount();
            s.commentCount = d.getCommentCount();
            return s;
        }

        State copy() {
            State s = new State();
            s.isLiked = isLiked; s.isBookmarked = isBookmarked;
            s.viewCount = viewCount; s.useCount = useCount; s.heartCount = heartCount;
            s.bookmarkCount = bookmarkCount; s.commentCount = commentCount;
            return s;
        }

        void applyTo(TemplateData d) {
            d.isLiked = isLiked;
            d.isBookmarked = isBookmarked;
            d.setViewCount(viewCount);
            d.setUseCount(useCount);
            d.setHeartCount(heartCount);
            d.setBookmarkCount(bookmarkCount);
            d.setCommentCount(commentCount);
        }
    }

    private final Map<String, State> states = new HashMap<>();
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> tickets = new HashMap<>();
    private final Set<String> viewReported = new HashSet<>();
    /** The account the cached flags belong to; another one (log in / out) must not inherit them. */
    private String stateOwner = "";

    private TemplateRepository() {
        this.api = RetrofitClient.getInstance().getTemplateApi();
    }

    public static synchronized TemplateRepository getInstance() {
        if (instance == null) {
            instance = new TemplateRepository();
        }
        return instance;
    }

    // ---- who is asking -------------------------------------------------------------------------------------------

    /** The signed-in username, or null. The server also gets the account's token, which is what it actually trusts. */
    public String viewer() {
        User user = AuthRepository.getInstance().getCurrentUser();
        if (user == null || user.getUsername() == null || user.getUsername().trim().isEmpty()) return null;
        return user.getUsername().trim();
    }

    public boolean isLoggedIn() {
        return viewer() != null;
    }

    private void ensureOwner() {
        String v = viewer();
        String now = v == null ? "" : v.toLowerCase();
        if (!now.equals(stateOwner)) {
            states.clear();
            tickets.clear();
            stateOwner = now;
        }
    }

    // ---- the shared state ----------------------------------------------------------------------------------------

    public void addStateListener(Consumer<String> listener) { listeners.addIfAbsent(listener); }
    public void removeStateListener(Consumer<String> listener) { listeners.remove(listener); }

    private void notifyChanged(String templateId) {
        for (Consumer<String> l : listeners) l.accept(templateId);
    }

    private static String key(String id) { return id == null ? "" : id.toLowerCase(); }

    private State stateOf(TemplateData d) {
        State s = states.get(key(d.getTemplateId()));
        return s != null ? s : State.of(d);
    }

    /** Brings a template object up to date with the newest thing the app knows about it. */
    public void applyCached(TemplateData d) {
        ensureOwner();
        State s = states.get(key(d.getTemplateId()));
        if (s != null) s.applyTo(d);
    }

    private void remember(TemplatePage page) {
        ensureOwner();
        if (page.items == null) { page.items = new java.util.ArrayList<>(); return; }
        page.items.removeIf(t -> t == null || t.getTemplateId() == null);
        for (TemplateData t : page.items) states.put(key(t.getTemplateId()), State.of(t));
    }

    // ---- lists ---------------------------------------------------------------------------------------------------

    public void fetchPage(TemplateFeedSource source, String cursor, int limit, Consumer<TemplatePage> ok, Consumer<String> fail) {
        ensureOwner();
        final String viewer = viewer();
        final String c = (cursor == null || cursor.isEmpty()) ? null : cursor;
        Call<TemplatePage> call;
        switch (source.kind) {
            case LIKED: call = api.liked(limit, c, viewer); break;
            case BOOKMARKED: call = api.bookmarked(limit, c, viewer); break;
            case SEARCH: call = api.search(source.query, limit, c, viewer); break;
            case FEED:
            default: call = api.feed(limit, c, viewer); break;
        }
        call.enqueue(new Callback<TemplatePage>() {
            @Override public void onResponse(Call<TemplatePage> call, Response<TemplatePage> response) {
                if (response.isSuccessful() && response.body() != null) {
                    final TemplatePage page = response.body();
                    Platform.runLater(() -> { remember(page); ok.accept(page); });
                } else {
                    final String message = "Error " + response.code();
                    Platform.runLater(() -> fail.accept(message));
                }
            }
            @Override public void onFailure(Call<TemplatePage> call, Throwable t) {
                Platform.runLater(() -> fail.accept("No connection"));
            }
        });
    }

    // ---- like / bookmark -----------------------------------------------------------------------------------------

    /**
     * Likes (liked == true) or unlikes. The screen answers at once; the request carries the wanted state, so a retry or
     * a double tap cannot undo itself; the server's numbers then replace ours. On failure everything goes back and
     * {@code onError} gets a message. Needs a logged-in user.
     */
    public void setLike(TemplateData d, boolean liked, Consumer<String> onError) {
        setInteraction(d, "like", liked, onError);
    }

    public void setBookmark(TemplateData d, boolean bookmarked, Consumer<String> onError) {
        setInteraction(d, "bookmark", bookmarked, onError);
    }

    private void setInteraction(TemplateData d, String kind, boolean value, Consumer<String> onError) {
        final String user = viewer();
        if (user == null) {
            if (onError != null) onError.accept("Please log in first");
            return;
        }
        ensureOwner();
        final String id = d.getTemplateId();
        final String k = key(id);
        final boolean isLike = "like".equals(kind);

        // 1. the instant answer
        final State previous = stateOf(d).copy();
        State optimistic = previous.copy();
        if (isLike) {
            if (optimistic.isLiked != value) optimistic.heartCount = Math.max(0, optimistic.heartCount + (value ? 1 : -1));
            optimistic.isLiked = value;
        } else {
            if (optimistic.isBookmarked != value) optimistic.bookmarkCount = Math.max(0, optimistic.bookmarkCount + (value ? 1 : -1));
            optimistic.isBookmarked = value;
        }
        states.put(k, optimistic);
        notifyChanged(id);
        final String ticketKey = kind + "|" + k;
        final int ticket = tickets.merge(ticketKey, 1, Integer::sum);

        // 2. ask the server
        Map<String, Object> body = new HashMap<>();
        body.put("username", user);
        body.put("templateId", id);
        body.put(isLike ? "liked" : "bookmarked", value);
        Call<JsonObject> call = isLike ? api.setLike(body) : api.setBookmark(body);
        call.enqueue(new Callback<JsonObject>() {
            @Override public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                final JsonObject answer = response.isSuccessful() ? response.body() : null;
                final int code = response.code();
                Platform.runLater(() -> {
                    boolean latest = Integer.valueOf(ticket).equals(tickets.get(ticketKey));
                    if (answer != null) {
                        // 3. the server's numbers replace ours (only if no newer tap happened meanwhile)
                        if (latest) {
                            State truth = stateOf(d).copy();
                            String flag = isLike ? "isLiked" : "isBookmarked";
                            if (answer.has(flag) && !answer.get(flag).isJsonNull()) {
                                boolean v = answer.get(flag).getAsBoolean();
                                if (isLike) truth.isLiked = v; else truth.isBookmarked = v;
                            }
                            readCounts(answer, truth);
                            states.put(k, truth);
                            notifyChanged(id);
                        }
                    } else {
                        revert(k, id, previous, latest);
                        if (onError != null) onError.accept("Error " + code);
                    }
                });
            }
            @Override public void onFailure(Call<JsonObject> call, Throwable t) {
                Platform.runLater(() -> {
                    revert(k, id, previous, Integer.valueOf(ticket).equals(tickets.get(ticketKey)));
                    if (onError != null) onError.accept("No connection");
                });
            }
        });
    }

    private void revert(String k, String id, State previous, boolean latest) {
        if (!latest) return;
        states.put(k, previous);
        notifyChanged(id);
    }

    // ---- view / use ----------------------------------------------------------------------------------------------

    /** The template was opened. The server counts it once per viewer per 30 minutes; this also skips repeats in this run. */
    public void postView(TemplateData d) {
        ensureOwner();
        if (!viewReported.add(key(d.getTemplateId()))) return;
        postCounter(api::incrementView, d, "newViewCount", (s, n) -> s.viewCount = n);
    }

    /** The template was actually used (exported). */
    public void postUse(TemplateData d) {
        ensureOwner();
        postCounter(api::incrementUse, d, "newUseCount", (s, n) -> s.useCount = n);
    }

    private interface Poster { Call<JsonObject> call(Map<String, Object> body); }
    private interface CountSetter { void set(State s, int n); }

    private void postCounter(Poster poster, TemplateData d, String answerField, CountSetter setter) {
        final String id = d.getTemplateId();
        Map<String, Object> body = new HashMap<>();
        body.put("templateId", id);
        String user = viewer();
        if (user != null) body.put("username", user);
        // Best effort: a lost counter must never bother the user.
        poster.call(body).enqueue(new Callback<JsonObject>() {
            @Override public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                final JsonObject answer = response.isSuccessful() ? response.body() : null;
                if (answer == null || !answer.has(answerField) || answer.get(answerField).isJsonNull()) return;
                final int n = answer.get(answerField).getAsInt();
                Platform.runLater(() -> {
                    State s = stateOf(d).copy();
                    setter.set(s, n);
                    states.put(key(id), s);
                    notifyChanged(id);
                });
            }
            @Override public void onFailure(Call<JsonObject> call, Throwable t) { }
        });
    }

    private static void readCounts(JsonObject o, State s) {
        if (o.has("viewCount") && !o.get("viewCount").isJsonNull()) s.viewCount = o.get("viewCount").getAsInt();
        if (o.has("useCount") && !o.get("useCount").isJsonNull()) s.useCount = o.get("useCount").getAsInt();
        if (o.has("heartCount") && !o.get("heartCount").isJsonNull()) s.heartCount = o.get("heartCount").getAsInt();
        if (o.has("bookmarkCount") && !o.get("bookmarkCount").isJsonNull()) s.bookmarkCount = o.get("bookmarkCount").getAsInt();
        if (o.has("commentCount") && !o.get("commentCount").isJsonNull()) s.commentCount = o.get("commentCount").getAsInt();
    }
}
