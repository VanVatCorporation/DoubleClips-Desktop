package com.vanvatcorporation.doubleclips.auth;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Template search on the server: the hot searches ("Trending"), suggestions while typing, and the log of a submitted search
 * (what the server ranks "Trending" from). The results themselves come from {@link TemplateFeedSource#search}. The same
 * endpoints and rules as the iOS app. Public methods are called on, and call back on, the JavaFX thread.
 */
public final class TemplateSearchRepository {

    private static final Gson GSON = new Gson();

    private TemplateSearchRepository() {}

    private static TemplateApiService api() {
        return RetrofitClient.getInstance().getTemplateApi();
    }

    /** The hot searches, best first. */
    public static void fetchTrending(int limit, Consumer<List<TrendingSearch>> ok, Consumer<String> fail) {
        api().trendingSearches(limit, TemplateRepository.getInstance().viewer()).enqueue(callback(
                json -> ok.accept(TemplateSearchParse.trending(GSON.fromJson(json, Object.class))), fail));
    }

    /** Suggested searches for {@code prefix}; an empty prefix is "you may like" (personal). */
    public static void fetchSuggestions(String prefix, int limit, Consumer<List<String>> ok, Consumer<String> fail) {
        api().suggestSearches(prefix, limit, TemplateRepository.getInstance().viewer()).enqueue(callback(
                json -> ok.accept(TemplateSearchParse.suggestions(GSON.fromJson(json, Object.class))), fail));
    }

    /** Tells the server a search was submitted. Best effort: nobody waits for it and a failure is ignored. */
    public static void logSearch(String query) {
        if (query == null || query.trim().isEmpty()) return;
        Map<String, Object> body = new HashMap<>();
        body.put("query", query.trim());
        String viewer = TemplateRepository.getInstance().viewer();
        if (viewer != null) body.put("username", viewer);
        api().logSearch(body).enqueue(new Callback<JsonObject>() {
            @Override public void onResponse(Call<JsonObject> call, Response<JsonObject> response) { }
            @Override public void onFailure(Call<JsonObject> call, Throwable t) { }
        });
    }

    private static Callback<JsonElement> callback(Consumer<JsonElement> ok, Consumer<String> fail) {
        return new Callback<JsonElement>() {
            @Override public void onResponse(Call<JsonElement> call, Response<JsonElement> response) {
                if (response.isSuccessful() && response.body() != null) {
                    final JsonElement body = response.body();
                    Platform.runLater(() -> ok.accept(body));
                } else {
                    final String message = "Error " + response.code();
                    Platform.runLater(() -> fail.accept(message));
                }
            }

            @Override public void onFailure(Call<JsonElement> call, Throwable t) {
                Platform.runLater(() -> fail.accept("No connection"));
            }
        };
    }
}
