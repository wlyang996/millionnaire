package com.millionnaire.gateway.safety;

import com.millionnaire.gateway.auth.UserStore;
import com.millionnaire.gateway.auth.WechatAuth;
import com.millionnaire.gateway.room.ClientException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Text is checked before mutation or broadcast; disabled never means PASS. */
@Component
public class ContentSafety {
    public record Verdict(String status, String traceId) {}
    @FunctionalInterface public interface Checker { Verdict check(String openid, String content, int scene); }
    private final boolean enabled;
    private final Checker checker;
    private final UserStore users;
    private final WechatAuth wechat;
    public ContentSafety(@Value("${millionnaire.content-safety.enabled:false}") boolean enabled,
                         @Value("${millionnaire.content-safety.timeout-ms:4000}") int timeout,
                         ObjectProvider<Checker> custom, UserStore users, WechatAuth wechat) {
        if (timeout < 1000 || timeout > 10000) throw new IllegalArgumentException("CONTENT_SAFETY_TIMEOUT_MS must be 1000..10000");
        this.enabled=enabled; this.users=users; this.wechat=wechat;
        this.checker=custom.getIfAvailable(() -> new WechatContentApi(timeout)::check);
    }
    public boolean enabled() { return enabled; }
    public Verdict chat(long userId, String content) {
        if (!enabled) return new Verdict("UNCHECKED", "");
        return check(users.openid(userId, wechat.appId()).orElse(null), content, 2);
    }
    public Verdict nickname(String openid, String content) { return check(openid, content, 1); }
    private Verdict check(String openid, String content, int scene) {
        if (!enabled) return new Verdict("UNCHECKED", "");
        if (openid == null || openid.isBlank()) throw new ClientException("CONTENT_ID_REQUIRED", "内容检测需要微信身份，请使用微信登录；测试环境可关闭内容检测");
        Verdict verdict=checker.check(openid,content,scene);
        if (verdict == null) throw unavailable();
        return switch (verdict.status()) {
            case "PASS" -> verdict;
            case "RISKY" -> throw new ClientException("CONTENT_REJECTED", "内容未通过安全检测，请修改后再试");
            case "REVIEW" -> throw new ClientException("CONTENT_REVIEW", "内容需要进一步审核，请修改后再试");
            default -> throw unavailable();
        };
    }
    public static ClientException unavailable() { return new ClientException("CONTENT_CHECK_UNAVAILABLE", "内容检测暂不可用，请稍后重试；未保存或发送本次内容"); }
}
