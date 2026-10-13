package com.millionnaire.gateway.characters;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import com.millionnaire.gateway.room.ClientException;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.function.Function;
import java.util.function.BiFunction;
import com.qcloud.cos.auth.COSCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared private COS transport; callers validate their own image/audio formats and size limits. */
@Component
public class CosCharacterStore implements CharacterObjectStore {
    private final String bucket;
    private final COSClient client;
    private final WechatStorageApi api;
    private final WechatCosCredentials credentials;
    @Autowired public CosCharacterStore(@Value("${millionnaire.cos.bucket:}") String bucket,
                             @Value("${millionnaire.cos.region:}") String region,
                             WechatStorageApi api, Clock clock) {
        this(bucket, region, api, clock, COSClient::new);
    }
    CosCharacterStore(String bucket, String region, WechatStorageApi api, Clock clock,
                      BiFunction<COSCredentialsProvider,ClientConfig,COSClient> factory) {
        this.bucket = bucket;
        this.api = api;
        this.credentials = new WechatCosCredentials(api, clock);
        if (bucket.isBlank() || region.isBlank()) { client = null; return; }
        var config = new ClientConfig(new Region(region));
        config.setHttpProtocol(HttpProtocol.https);
        config.setConnectionTimeout(10000);
        config.setSocketTimeout(20000);
        client = factory.apply(credentials, config);
    }
    public boolean ready() { return client != null; }
    @Override public void verify() { require(); credentials.getCredentials(); }
    public void put(String key, byte[] png) {
        put(key, png, "image/png");
    }
    public void put(String key, byte[] data, String contentType) {
        require();
        try {
            var meta = new ObjectMetadata();
            meta.setContentLength(data.length);
            meta.setContentType(contentType);
            meta.setCacheControl("public, max-age=31536000, immutable");
            // Admin uploads use an empty openid, as specified by the CloudRun COS SDK guide.
            meta.addUserMetadata("fileid", api.metadata(bucket, key));
            execute(c -> c.putObject(bucket, key, new ByteArrayInputStream(data), meta));
        } catch (RuntimeException e) { throw failure(e, "COS 上传失败，请检查桶名、地域及微信云托管云调用配置"); }
    }
    public byte[] get(String key) {
        return get(key, CharacterImages.MAX_BYTES);
    }
    public byte[] get(String key, int maxBytes) {
        require();
        try (var in = execute(c -> c.getObject(bucket, key)).getObjectContent()) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IllegalStateException("oversized object");
            return bytes;
        } catch (Exception e) { throw failure(e, "COS 资源读取失败，请检查桶名、地域及微信云托管云调用配置"); }
    }
    private void require() {
        if (!ready()) throw new ClientException("COS_NOT_CONFIGURED", "请配置 COS_BUCKET、COS_REGION；无需配置长期密钥");
    }
    private <T> T execute(Function<COSClient,T> action) {
        try { return action.apply(client); }
        catch (CosServiceException e) {
            if (!"ExpiredToken".equals(e.getErrorCode()) && !"TokenExpired".equals(e.getErrorCode())) throw e;
            credentials.refresh();
            return action.apply(client); // one bounded retry for an expired token, never retry permission errors
        }
    }
    private static ClientException failure(Exception e, String message) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) if (cause instanceof ClientException ce) return ce;
        return new ClientException("COS_FAILED", message);
    }
    @PreDestroy void close() { if (client != null) client.shutdown(); }
}
