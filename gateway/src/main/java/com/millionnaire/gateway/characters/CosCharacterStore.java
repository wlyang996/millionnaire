package com.millionnaire.gateway.characters;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import com.millionnaire.gateway.room.ClientException;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Private COS bucket. Published PNGs are delivered by the gateway, never exposing credentials. */
@Component
public class CosCharacterStore implements CharacterObjectStore {
    private final String bucket;
    private final COSClient client;
    public CosCharacterStore(@Value("${millionnaire.cos.bucket:}") String bucket,
                             @Value("${millionnaire.cos.region:}") String region,
                             @Value("${millionnaire.cos.secret-id:}") String id,
                             @Value("${millionnaire.cos.secret-key:}") String key) {
        this.bucket = bucket;
        if (bucket.isBlank() || region.isBlank() || id.isBlank() || key.isBlank()) { client = null; return; }
        var config = new ClientConfig(new Region(region));
        config.setHttpProtocol(HttpProtocol.https);
        config.setConnectionTimeout(10000);
        config.setSocketTimeout(20000);
        client = new COSClient(new BasicCOSCredentials(id, key), config);
    }
    public boolean ready() { return client != null; }
    public void put(String key, byte[] png) {
        require();
        try {
            var meta = new ObjectMetadata();
            meta.setContentLength(png.length);
            meta.setContentType("image/png");
            meta.setCacheControl("public, max-age=31536000, immutable");
            client.putObject(bucket, key, new ByteArrayInputStream(png), meta);
        } catch (RuntimeException e) { throw new ClientException("COS_FAILED", "COS 上传失败，请检查地域、桶名和写入权限"); }
    }
    public byte[] get(String key) {
        require();
        try (var in = client.getObject(bucket, key).getObjectContent()) {
            byte[] bytes = in.readNBytes(CharacterImages.MAX_BYTES + 1);
            if (bytes.length > CharacterImages.MAX_BYTES) throw new IllegalStateException("oversized object");
            return bytes;
        } catch (Exception e) { throw new ClientException("COS_FAILED", "COS 图片读取失败"); }
    }
    private void require() {
        if (!ready()) throw new ClientException("COS_NOT_CONFIGURED", "请配置 COS_BUCKET、COS_REGION、COS_SECRET_ID、COS_SECRET_KEY");
    }
    @PreDestroy void close() { if (client != null) client.shutdown(); }
}
