package com.vanvatcorporation.doubleclips.auth;

import com.vanvatcorporation.doubleclips.data.TemplateData;
import javafx.application.Platform;
import javafx.beans.property.ListProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.collections.FXCollections;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class TemplateRepository {

    private static TemplateRepository instance;
    private final TemplateApiService api;
    private final ListProperty<TemplateData> templates = new SimpleListProperty<>(FXCollections.observableArrayList());

    private TemplateRepository() {
        this.api = RetrofitClient.getInstance().getTemplateApi();
    }

    public static synchronized TemplateRepository getInstance() {
        if (instance == null) {
            instance = new TemplateRepository();
        }
        return instance;
    }

    public ListProperty<TemplateData> templatesProperty() {
        return templates;
    }

    public void fetchTemplates() {
        api.fetchTemplates().enqueue(new Callback<List<TemplateData>>() {
            @Override
            public void onResponse(Call<List<TemplateData>> call, Response<List<TemplateData>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    List<TemplateData> fetchedTemplates = response.body();
                    
                    // Now fetch interactions to enrich the data
                    fetchUserInteractions(fetchedTemplates);
                }
            }

            @Override
            public void onFailure(Call<List<TemplateData>> call, Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private void fetchUserInteractions(List<TemplateData> data) {
        // We fetch liked and bookmarked separately, just like Android
        api.fetchLikedTemplateIds().enqueue(new Callback<List<String>>() {
            @Override
            public void onResponse(Call<List<String>> call, Response<List<String>> likedResponse) {
                List<String> likedIds = likedResponse.isSuccessful() ? likedResponse.body() : new ArrayList<>();
                
                api.fetchBookmarkedTemplateIds().enqueue(new Callback<List<String>>() {
                    @Override
                    public void onResponse(Call<List<String>> call, Response<List<String>> bookmarkedResponse) {
                        List<String> bookmarkedIds = bookmarkedResponse.isSuccessful() ? bookmarkedResponse.body() : new ArrayList<>();
                        
                        // Map interactions
                        for (TemplateData template : data) {
                            if (likedIds != null && likedIds.contains(template.getTemplateId())) {
                                template.isLiked = true;
                            }
                            if (bookmarkedIds != null && bookmarkedIds.contains(template.getTemplateId())) {
                                template.isBookmarked = true;
                            }
                        }

                        Platform.runLater(() -> templates.setAll(data));
                    }

                    @Override
                    public void onFailure(Call<List<String>> call, Throwable t) {
                        Platform.runLater(() -> templates.setAll(data));
                    }
                });
            }

            @Override
            public void onFailure(Call<List<String>> call, Throwable t) {
                Platform.runLater(() -> templates.setAll(data));
            }
        });
    }

    // ---- interactions (the same calls the mobile apps make) ------------------------------------------------

    /** Null when nobody is logged in. */
    private Map<String, String> bodyFor(TemplateData data) {
        com.vanvatcorporation.doubleclips.auth.User user = AuthRepository.getInstance().getCurrentUser();
        if (user == null || user.getUsername() == null) return null;
        Map<String, String> body = new HashMap<>();
        body.put("username", user.getUsername());
        body.put("templateId", data.getTemplateId());
        return body;
    }

    public boolean isLoggedIn() {
        return AuthRepository.getInstance().getCurrentUser() != null;
    }

    /** Toggles the like on the server; {@code done} gets true / false on the FX thread. The caller updates its own state. */
    public void toggleLike(TemplateData data, Consumer<Boolean> done) {
        post(api::toggleLike, data, done);
    }

    public void toggleBookmark(TemplateData data, Consumer<Boolean> done) {
        post(api::toggleBookmark, data, done);
    }

    /** Counts a view (only for a logged-in user; the server counts per user). Fire and forget. */
    public void markSeen(TemplateData data) {
        Map<String, String> body = bodyFor(data);
        if (body == null) return;
        api.seenTemplate(body).enqueue(new Callback<okhttp3.ResponseBody>() {
            @Override public void onResponse(Call<okhttp3.ResponseBody> call, Response<okhttp3.ResponseBody> response) { }
            @Override public void onFailure(Call<okhttp3.ResponseBody> call, Throwable t) { }
        });
    }

    private interface Poster {
        Call<okhttp3.ResponseBody> call(Map<String, String> body);
    }

    private void post(Poster poster, TemplateData data, Consumer<Boolean> done) {
        Map<String, String> body = bodyFor(data);
        if (body == null) {
            Platform.runLater(() -> done.accept(false));
            return;
        }
        poster.call(body).enqueue(new Callback<okhttp3.ResponseBody>() {
            @Override public void onResponse(Call<okhttp3.ResponseBody> call, Response<okhttp3.ResponseBody> response) {
                boolean ok = response.isSuccessful();
                Platform.runLater(() -> done.accept(ok));
            }
            @Override public void onFailure(Call<okhttp3.ResponseBody> call, Throwable t) {
                Platform.runLater(() -> done.accept(false));
            }
        });
    }
}
