package com.millionnaire.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** 服务端随机值：用户与房间 ID 取 1..2^53-1（JS 可安全表示），十进制字符串即引擎的 playerId / roomId。 */
public final class Ids {
    public static final long MAX_ID = 9007199254740991L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static long nextId() {
        return RANDOM.nextLong(MAX_ID) + 1;
    }

    /** 引擎随机种子：只保存在服务端。 */
    public static long seed() {
        return RANDOM.nextLong();
    }

    /** 六位房间号 000000..999999。 */
    public static int roomCode() {
        return RANDOM.nextInt(1_000_000);
    }

    public static String token() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] bytes16() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return b;
    }

    /** 把任意客户端请求 ID 压成 16 字节（数据库幂等键）。 */
    public static byte[] digest16(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(d, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
