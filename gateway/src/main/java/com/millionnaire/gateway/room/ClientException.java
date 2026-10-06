package com.millionnaire.gateway.room;

/** 可直接告诉客户端的错误（错误码 + 说明），不是引擎的规则拒绝。 */
public class ClientException extends RuntimeException {
    private final String code;

    public ClientException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
