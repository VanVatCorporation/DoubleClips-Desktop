package com.vanvatcorporation.doubleclips.auth;

import com.vanvatcorporation.doubleclips.data.TemplateData;
import retrofit2.Call;
import retrofit2.http.GET;

import java.util.List;

public interface TemplateApiService {
    @GET("/doubleclips/api/fetch-templates")
    Call<List<TemplateData>> fetchTemplates();

    @GET("/doubleclips/api/fetch-liked-templates")
    Call<List<String>> fetchLikedTemplateIds();

    @GET("/doubleclips/api/fetch-bookmarked-templates")
    Call<List<String>> fetchBookmarkedTemplateIds();
}
