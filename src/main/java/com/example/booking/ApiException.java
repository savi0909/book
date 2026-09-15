package com.example.booking;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }
    static ApiException missing() { return new ApiException(404, "NOT_FOUND", "Resource not found"); }
}
