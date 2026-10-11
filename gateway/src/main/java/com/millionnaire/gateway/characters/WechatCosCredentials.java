package com.millionnaire.gateway.characters;

import com.qcloud.cos.auth.*;
import com.millionnaire.gateway.room.ClientException;
import java.time.Clock;

/** Lazily caches CloudRun temporary credentials and refreshes before expiry. */
final class WechatCosCredentials implements COSCredentialsProvider {
    private final WechatStorageApi api;
    private final Clock clock;
    private COSCredentials cached;
    private long expiresAt;
    WechatCosCredentials(WechatStorageApi api, Clock clock) { this.api = api; this.clock = clock; }
    @Override public synchronized COSCredentials getCredentials() {
        long now = clock.instant().getEpochSecond();
        if (cached != null && expiresAt > now + 60) return cached;
        var data = api.credentials();
        String id = data.path("TmpSecretId").asText(""), key = data.path("TmpSecretKey").asText(""), token = data.path("Token").asText("");
        long expiration = data.path("ExpiredTime").asLong(0);
        if (id.isBlank() || key.isBlank() || token.isBlank() || expiration <= clock.instant().getEpochSecond() + 30) {
            cached = null;
            throw new ClientException("COS_AUTH_FAILED", "微信云托管返回的临时凭证不完整或已过期，请检查云调用配置和容器时间");
        }
        cached = new BasicSessionCredentials(id, key, token);
        expiresAt = expiration;
        return cached;
    }
    @Override public synchronized void refresh() { cached = null; expiresAt = 0; }
}
