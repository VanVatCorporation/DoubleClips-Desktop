package com.vanvatcorporation.doubleclips.auth;

import javafx.application.Platform;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import java.util.TimeZone;
import java.util.function.Consumer;

/**
 * "My statistics" from the server (Profile -> Statistics): totals, who came and used the templates day by day, and the account's
 * templates with their counters. The server does the counting, so nothing here pages through the template list.
 * Called on, and calls back on, the JavaFX thread.
 */
public final class AuthorStatsRepository {

    private AuthorStatsRepository() {}

    /** @param days 7..90; the server also loads the same number of days before it, for the trend */
    public static void fetch(int days, Consumer<AuthorStats> ok, Consumer<String> fail) {
        final String viewer = TemplateRepository.getInstance().viewer();
        if (viewer == null) { fail.accept("Please log in first"); return; }

        // Days are the user's local days: tell the server how far this computer is from UTC (minutes east, Vietnam = 420).
        final int tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;

        RetrofitClient.getInstance().getTemplateApi().authorStats(days, tz, viewer).enqueue(new Callback<AuthorStats>() {
            @Override public void onResponse(Call<AuthorStats> call, Response<AuthorStats> response) {
                if (response.isSuccessful() && response.body() != null) {
                    final AuthorStats body = response.body();
                    Platform.runLater(() -> ok.accept(body));
                } else {
                    final String message = "Error " + response.code();
                    Platform.runLater(() -> fail.accept(message));
                }
            }

            @Override public void onFailure(Call<AuthorStats> call, Throwable t) {
                Platform.runLater(() -> fail.accept("No connection"));
            }
        });
    }
}
