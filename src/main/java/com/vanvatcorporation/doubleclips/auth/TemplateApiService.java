package com.vanvatcorporation.doubleclips.auth;

import com.vanvatcorporation.doubleclips.data.TemplateData;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

import java.util.List;
import java.util.Map;

public interface TemplateApiService {
    @GET("/doubleclips/api/fetch-templates")
    Call<List<TemplateData>> fetchTemplates();

    @GET("/doubleclips/api/fetch-liked-templates")
    Call<List<String>> fetchLikedTemplateIds();

    @GET("/doubleclips/api/fetch-bookmarked-templates")
    Call<List<String>> fetchBookmarkedTemplateIds();

    // Body: {"username": ..., "templateId": ...} - the same posts the mobile apps make.
    @POST("/doubleclips/api/toggle-like")
    Call<ResponseBody> toggleLike(@Body Map<String, String> body);

    @POST("/doubleclips/api/toggle-bookmark")
    Call<ResponseBody> toggleBookmark(@Body Map<String, String> body);

    @POST("/doubleclips/api/seen-template")
    Call<ResponseBody> seenTemplate(@Body Map<String, String> body);
}
