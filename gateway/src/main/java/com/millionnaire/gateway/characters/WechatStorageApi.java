package com.millionnaire.gateway.characters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.gateway.room.ClientException;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** Official CloudRun open APIs, resolved inside the container without permanent secrets. */
@Component
public class WechatStorageApi {
    private final URI base;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    public WechatStorageApi() {
        this(URI.create("http://api.weixin.qq.com"));
    }
    // Alternate base is only used by package tests; production uses the official fixed endpoint.
    WechatStorageApi(URI base) {
        this.base = base;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public JsonNode credentials() { return call("/_/cos/getauth", null, "COS_AUTH_FAILED", "临时凭证获取失败，请在微信云托管云调用中放行 /_/cos/getauth；此接口须在云托管容器内调用"); }
    public String metadata(String bucket, String key) {
        String code = "COS_META_FAILED", message = "上传元数据获取失败，请在微信云托管云调用中放行 /_/cos/metaid/encode";
        var response = call("/_/cos/metaid/encode", Map.of("openid", "", "bucket", bucket, "paths", java.util.List.of(key)), code, message);
        var values = response.path("respdata").path("x_cos_meta_field_strs");
        if (!values.isArray() || values.size() != 1 || !values.get(0).isTextual() || values.get(0).asText().isBlank()) throw new ClientException(code, message);
        return values.get(0).asText();
    }
    private JsonNode call(String path, Object body, String code, String message) {
        try {
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
            if (body == null) request.GET();
            else request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            // Completion waits for the bounded body too, so the request timeout covers body reads.
            var response = http.send(request.build(), boundedBody());
            if (response.statusCode() != 200) throw new IOException("open API HTTP error");
            var result = json.readTree(response.body());
            if (result == null || !result.isObject() || (result.has("errcode") && result.path("errcode").asInt(-1) != 0)) throw new IOException("open API rejected");
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new ClientException(code, message);
        } catch (Exception e) {
            // Never expose response bodies, temporary keys, or tokens in exceptions or logs.
            throw new ClientException(code, message);
        }
    }
    private static HttpResponse.BodyHandler<byte[]> boundedBody() {
        return info -> new HttpResponse.BodySubscriber<>() {
            final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            Flow.Subscription subscription;
            int received;
            boolean rejected;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription s) { subscription = s; delegate.onSubscribe(s); }
            public void onNext(List<ByteBuffer> buffers) {
                if (rejected) return;
                for (var buffer : buffers) {
                    if (buffer.remaining() > 32768 - received) {
                        rejected = true; subscription.cancel(); delegate.onError(new IOException("oversized open API response")); return;
                    }
                    received += buffer.remaining();
                }
                delegate.onNext(buffers);
            }
            public void onError(Throwable t) { if (!rejected) delegate.onError(t); }
            public void onComplete() { if (!rejected) delegate.onComplete(); }
        };
    }
}
