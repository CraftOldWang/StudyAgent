package com.studyagent.common.response;

/**
 * REST 接口统一响应体。
 */
public record ApiResponse<T>(
        int code,
        String message,
        T data
) {
    /**
     * 构造成功响应。
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /**
     * 构造失败响应。
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
