package com.vanvatcorporation.doubleclips.auth;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class AuthRepository {
    private static AuthRepository instance;
    private final ApiService apiService;

    // Cached User Object (In-Memory)
    private User currentUser;

    // JavaFX Property for reactive UI updates
    private final ObjectProperty<User> userProperty = new SimpleObjectProperty<>();

    public interface AuthCallback<T> {
        void onSuccess(T data);
        void onError(String message);
    }

    private AuthRepository() {
        apiService = RetrofitClient.getInstance().getApi();
    }

    public static synchronized AuthRepository getInstance() {
        if (instance == null) {
            instance = new AuthRepository();
        }
        return instance;
    }

    public User getCurrentUser() {
        return currentUser;
    }

    public ObjectProperty<User> userProperty() {
        return userProperty;
    }

    public void login(String email, String password, AuthCallback<User> callback) {
        LoginRequest request = new LoginRequest(email, password);
        apiService.login(request).enqueue(new Callback<ApiResponse<User>>() {
            @Override
            public void onResponse(Call<ApiResponse<User>> call, Response<ApiResponse<User>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().isSuccess()) {
                        User user = response.body().getUser();
                        cacheUser(user);
                        callback.onSuccess(user);
                    } else {
                        callback.onError(response.body().getError());
                    }
                } else {
                    callback.onError("Login failed: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<User>> call, Throwable t) {
                callback.onError("Network error: " + t.getMessage());
            }
        });
    }

    public void checkSession(AuthCallback<User> callback) {
        apiService.getProfile().enqueue(new Callback<ApiResponse<User>>() {
            @Override
            public void onResponse(Call<ApiResponse<User>> call, Response<ApiResponse<User>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().getUser() != null) {
                    User user = response.body().getUser();
                    cacheUser(user);
                    callback.onSuccess(user);
                } else {
                    clearUser();
                    callback.onError("Not logged in");
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<User>> call, Throwable t) {
                callback.onError("Network error: " + t.getMessage());
            }
        });
    }

    public void logout(AuthCallback<Void> callback) {
        apiService.logout().enqueue(new Callback<ApiResponse<Void>>() {
            @Override
            public void onResponse(Call<ApiResponse<Void>> call, Response<ApiResponse<Void>> response) {
                clearUser();
                clearLocalSession();
                callback.onSuccess(null);
            }

            @Override
            public void onFailure(Call<ApiResponse<Void>> call, Throwable t) {
                clearUser();
                clearLocalSession();
                callback.onSuccess(null);
            }
        });
    }

    private void clearLocalSession() {
        RetrofitClient.getInstance().clearCookies();
    }

    private void cacheUser(User user) {
        this.currentUser = user;
        Platform.runLater(() -> userProperty.set(user));
    }

    private void clearUser() {
        this.currentUser = null;
        Platform.runLater(() -> userProperty.set(null));
    }
}
