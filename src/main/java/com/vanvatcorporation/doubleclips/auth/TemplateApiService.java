package com.vanvatcorporation.doubleclips.auth;

import com.google.gson.JsonObject;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

import java.util.Map;

/**
 * The Templates backend (app.vanvatcorp.com, backend v2). Contract: docs/doubleclips-template-api-v2.md.
 * A null @Query value is left out of the request. The account's token and the device id are added to every
 * call by the interceptor in RetrofitClient.
 */
public interface TemplateApiService {
    /** The per-account suggestion feed. */
    @GET("/doubleclips/api/templates/feed")
    Call<TemplatePage> feed(@Query("limit") int limit, @Query("cursor") String cursor, @Query("viewer") String viewer);

    /** Server-side search (newest first). */
    @GET("/doubleclips/api/templates")
    Call<TemplatePage> search(@Query("q") String q, @Query("limit") int limit, @Query("cursor") String cursor, @Query("viewer") String viewer);

    /** One author's templates, newest first ("My Templates" = the signed-in account as the author). */
    @GET("/doubleclips/api/templates")
    Call<TemplatePage> byAuthor(@Query("author") String author, @Query("limit") int limit, @Query("cursor") String cursor, @Query("viewer") String viewer, @Query("sort") String sort);

    /** Hot searches ("Trending"), best first. Rows: strings or {query, count?, hot?}. */
    @GET("/doubleclips/api/templates/search/trending")
    Call<com.google.gson.JsonElement> trendingSearches(@Query("limit") int limit, @Query("viewer") String viewer);

    /** Suggested searches for what was typed; an empty q asks for "you may like" (personal, by the viewer). */
    @GET("/doubleclips/api/templates/search/suggest")
    Call<com.google.gson.JsonElement> suggestSearches(@Query("q") String q, @Query("limit") int limit, @Query("viewer") String viewer);

    // Body: { query, username? } - a search the user submitted, so the server can rank "Trending". Best effort.
    @POST("/doubleclips/api/templates/search/log")
    Call<JsonObject> logSearch(@Body Map<String, Object> body);

    /** "My statistics": totals, per-day activity of other people and the author's templates (needs an account). */
    @GET("/doubleclips/api/templates/stats")
    Call<AuthorStats> authorStats(@Query("days") int days, @Query("tz") int tzMinutes, @Query("viewer") String viewer);

    @GET("/doubleclips/api/templates/liked")
    Call<TemplatePage> liked(@Query("limit") int limit, @Query("cursor") String cursor, @Query("viewer") String viewer);

    @GET("/doubleclips/api/templates/bookmarked")
    Call<TemplatePage> bookmarked(@Query("limit") int limit, @Query("cursor") String cursor, @Query("viewer") String viewer);

    // Body: { username, templateId, liked: true|false } - the wanted state, so a retry cannot undo itself.
    @POST("/doubleclips/api/toggle-like")
    Call<JsonObject> setLike(@Body Map<String, Object> body);

    // Body: { username, templateId, bookmarked: true|false }
    @POST("/doubleclips/api/toggle-bookmark")
    Call<JsonObject> setBookmark(@Body Map<String, Object> body);

    // Body: { templateId, username? } - counted once per viewer per 30 minutes.
    @POST("/doubleclips/api/increment-view")
    Call<JsonObject> incrementView(@Body Map<String, Object> body);

    // Body: { templateId, username? } - the template was actually used (exported).
    @POST("/doubleclips/api/increment-use")
    Call<JsonObject> incrementUse(@Body Map<String, Object> body);
}
