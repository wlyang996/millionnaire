package com.millionnaire.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
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

    /** 把 code 换成 openid；测试里替换成假的。 */
    @FunctionalInterface
    public interface CodeExchanger {
        /** @return openid；code 无效或微信出错时为空 */
        Optional<String> openid(String appId, String secret, String code);
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
    }

    public boolean configured() {
        return !appId.isEmpty() && !secret.isEmpty();
    }

    public String appId() {
        return appId;
    }

    public Optional<String> openid(String code) {
        if (!configured() || code == null || code.isBlank() || code.length() > 128) {
            return Optional.empty();
        }
        return exchanger.openid(appId, secret, code.strip());
    }

    /** 真实实现：GET {apiBase}/sns/jscode2session。 */
    static CodeExchanger http(String apiBase, ObjectMapper json) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (appId, secret, code) -> {
            String url = apiBase.replaceAll("/+$", "") + "/sns/jscode2session?appid=" + enc(appId) + "&secret=" + enc(secret)
                    + "&js_code=" + enc(code) + "&grant_type=authorization_code";
            try {
                HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonNode body = json.readTree(r.body());
                String openid = body.path("openid").asText("");
                if (r.statusCode() != 200 || openid.isEmpty()) {
                    // 不记录 code 与 secret
                    log.warn("jscode2session failed: http {} errcode {} errmsg {}", r.statusCode(), body.path("errcode").asInt(),
                            body.path("errmsg").asText());
                    return Optional.empty();
                }
                return Optional.of(openid);
            } catch (IOException e) {
                log.warn("jscode2session unreachable: {}", e.toString());
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
