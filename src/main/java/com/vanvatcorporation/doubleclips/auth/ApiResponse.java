package com.vanvatcorporation.doubleclips.auth;

public class ApiResponse<T> {
    private boolean success;
    private String message;
    private String error;
    private T user;

    // Getters
    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getError() {
        return error;
    }

    public T getUser() {
        return user;
    }
}
