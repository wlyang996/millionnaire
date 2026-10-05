package com.millionnaire.engine.serialize;

import java.lang.reflect.Type;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * {@link Codec#DEFAULT}（仅 sealed 多态）的静态门面，以及 SHA-256 工具。
 * 含开放接口（Command/Event/DomainState）的对象须使用引擎提供的 Codec。
 */
public final class Canonical {
    private Canonical() {
    }

    public static String encode(Object value) {
        return Codec.DEFAULT.encode(value);
    }

    public static String encode(Object value, Type declared) {
        return Codec.DEFAULT.encode(value, declared);
    }

    public static byte[] bytes(Object value) {
        return Codec.DEFAULT.bytes(value);
    }

    public static <T> T decode(String text, Class<T> type) {
        return Codec.DEFAULT.decode(text, type);
    }

    public static <T> T decode(byte[] utf8, Class<T> type) {
        return Codec.DEFAULT.decode(utf8, type);
    }

    /** 规范字节的 SHA-256（结构哈希，见 {@link Codec}）。 */
    public static String sha256Hex(Object value) {
        return sha256Hex(bytes(value));
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
