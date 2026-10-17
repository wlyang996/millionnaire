package com.millionnaire.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.gateway.diagnostics.ConnectionDiagnostics;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 微信小游戏登录：客户端 wx.login 拿到的一次性 code，由服务端调 jscode2session 换成 openid。
 * AppID 与 AppSecret 只从环境变量读取（云托管"服务设置"里配置 WECHAT_APPID / WECHAT_APPSECRET），不进仓库；
 * 不保存 session_key（只用于解密用户数据，本游戏不需要）。没配置时 {@link #configured()} 为 false，接口返回 WECHAT_NOT_CONFIGURED。
 */
@Component
public class WechatAuth {
    private static final Logger log = LoggerFactory.getLogger(WechatAuth.class);

    /** 换取结果：成功时 openid 非空；失败时 errcode 为微信错误码（40029 code 无效、40125 AppSecret 无效、40013 AppID 无效、-1 网络不通）。 */
    public record Exchange(String openid, int errcode, String errmsg) {
        public static Exchange ok(String openid) {
            return new Exchange(openid, 0, "");
        }

        public static Exchange fail(int errcode, String errmsg) {
            return new Exchange(null, errcode, errmsg);
        }
    }

    /** 把 code 换成 openid；测试里替换成假的。 */
    @FunctionalInterface
    public interface CodeExchanger {
        Exchange exchange(String appId, String secret, String code);
    }

    private final String appId;
    private final String secret;
    private final CodeExchanger exchanger;

    public WechatAuth(@Value("${millionnaire.wechat.app-id:}") String appId,
                      @Value("${millionnaire.wechat.app-secret:}") String secret,
                      @Value("${millionnaire.wechat.api-base:https://api.weixin.qq.com}") String apiBase,
                      ObjectMapper json, org.springframework.beans.factory.ObjectProvider<CodeExchanger> custom) {
        this.appId = appId == null ? "" : appId.strip();
        this.secret = secret == null ? "" : secret.strip();
        this.exchanger = custom.getIfAvailable(() -> http(apiBase, json));
        log.info("[MN-NET] wechat.config appIdConfigured={} secretConfigured={}", !this.appId.isEmpty(), !this.secret.isEmpty());
    }

    public boolean configured() {
        return !appId.isEmpty() && !secret.isEmpty();
    }

    public String appId() {
        return appId;
    }

    public Exchange exchange(String code) {
        long started = System.nanoTime();
        log.info("[MN-NET] wechat.exchange.start trace={} configured={} hasCode={}", ConnectionDiagnostics.trace(), configured(), code != null && !code.isBlank());
        if (!configured() || code == null || code.isBlank() || code.length() > 128) {
            log.warn("[MN-NET] wechat.exchange.rejected trace={} configured={} validCode={}", ConnectionDiagnostics.trace(), configured(), code != null && !code.isBlank() && code.length() <= 128);
            return Exchange.fail(40029, "invalid code");
        }
        Exchange result = exchanger.exchange(appId, secret, code.strip());
        log.info("[MN-NET] wechat.exchange.end trace={} ok={} errcode={} elapsedMs={}", ConnectionDiagnostics.trace(), result.openid() != null,
                result.errcode(), (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    /**
     * 真实实现：GET {apiBase}/sns/jscode2session。HTTP/1.1；HTTPS 连不上时改用 http://api.weixin.qq.com 再试一次
     * （云托管开启"开放接口服务"后，容器访问 api.weixin.qq.com 走云调用转发，按文档应使用 HTTP）。
     */
    static CodeExchanger http(String apiBase, ObjectMapper json) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        String primary = apiBase.replaceAll("/+$", "");
        String fallback = primary.startsWith("https://") ? "http://" + primary.substring("https://".length()) : null;
        return (appId, secret, code) -> {
            Exchange r = call(client, json, primary, appId, secret, code);
            if (r.errcode() == -1 && fallback != null) {
                log.warn("[MN-NET] wechat.http.fallback trace={} transport=http", ConnectionDiagnostics.trace());
                Exchange second = call(client, json, fallback, appId, secret, code);
                return second.errcode() == -1 ? Exchange.fail(-1, r.errmsg() + " | " + second.errmsg()) : second;
            }
            return r;
        };
    }

    private static Exchange call(HttpClient client, ObjectMapper json, String base, String appId, String secret, String code) {
        long started = System.nanoTime();
        String transport = base.startsWith("https:") ? "https" : "http";
        String url = base + "/sns/jscode2session?appid=" + enc(appId) + "&secret=" + enc(secret)
                + "&js_code=" + enc(code) + "&grant_type=authorization_code";
        try {
            log.info("[MN-NET] wechat.http.start trace={} transport={} operation=jscode2session", ConnectionDiagnostics.trace(), transport);
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.info("[MN-NET] wechat.http.end trace={} transport={} status={} elapsedMs={}", ConnectionDiagnostics.trace(), transport,
                    r.statusCode(), (System.nanoTime() - started) / 1_000_000);
            JsonNode body = json.readTree(r.body());
            String openid = body == null ? "" : body.path("openid").asText("");
            if (r.statusCode() != 200 || openid.isEmpty()) {
                int errcode = body == null ? r.statusCode() : body.path("errcode").asInt(r.statusCode());
                log.warn("[MN-NET] wechat.http.rejected trace={} transport={} status={} errcode={}", ConnectionDiagnostics.trace(), transport, r.statusCode(), errcode);
                // Provider text is returned for diagnosis, but never write its body or credential-bearing URI into logs.
                return Exchange.fail(errcode, body == null ? "empty response" : body.path("errmsg").asText(""));
            }
            return Exchange.ok(openid);
        } catch (IOException e) {
            String kind = ConnectionDiagnostics.failureKind(e);
            log.warn("[MN-NET] wechat.http.fail trace={} transport={} kind={} elapsedMs={}", ConnectionDiagnostics.trace(), transport, kind,
                    (System.nanoTime() - started) / 1_000_000);
            return Exchange.fail(-1, transport + " " + kind);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[MN-NET] wechat.http.interrupted trace={} transport={}", ConnectionDiagnostics.trace(), transport);
            return Exchange.fail(-1, "interrupted");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
