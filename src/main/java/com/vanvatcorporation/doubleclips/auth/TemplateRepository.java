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
import java.util.List;

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
}
