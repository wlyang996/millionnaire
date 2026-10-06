package com.millionnaire.gateway.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 登录令牌 → 用户。只在内存：服务重启后房间与对局本来就解散（lean 设计），客户端重新登录即可。
 */
@Component
public class SessionTokens {
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    public String issue(long userId) {
        String token = Ids.token();
        tokens.put(token, userId);
        return token;
    }

    public Optional<Long> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokens.get(token.strip()));
    }

    /** 从 "Bearer xxx" 或裸令牌中取用户。 */
    public Optional<Long> fromAuthorization(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String h = header.strip();
        return resolve(h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7) : h);
    }
}
