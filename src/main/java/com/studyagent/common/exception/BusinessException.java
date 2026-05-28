package com.studyagent.common.exception;

/**
 * 业务异常，携带可返回给前端的错误码和错误信息。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    /**
     * 使用默认 400 错误码创建业务异常。
     */
    public BusinessException(String message) {
        this(400, message);
    }

    /**
     * 使用指定错误码创建业务异常。
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 返回业务错误码。
     */
    public int getCode() {
        return code;
    }
}
