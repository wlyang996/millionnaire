package com.millionnaire.engine.config;

import java.util.List;

/** 配置校验失败；携带全部错误信息。 */
public final class ConfigValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient List<String> errors;

    public ConfigValidationException(List<String> errors) {
        super("invalid rule config: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
