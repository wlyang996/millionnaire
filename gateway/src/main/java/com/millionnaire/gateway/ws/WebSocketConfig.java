package com.millionnaire.gateway.ws;

import com.millionnaire.gateway.auth.SessionTokens;
import com.millionnaire.gateway.diagnostics.ConnectionDiagnostics;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** WebSocket 入口 /ws。令牌可放在查询参数 token、请求头 Authorization，或连接后的第一条 AUTH 消息里。 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final GameSocketHandler handler;
    private final SessionTokens tokens;

    public WebSocketConfig(GameSocketHandler handler, SessionTokens tokens) {
        this.handler = handler;
        this.tokens = tokens;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .addInterceptors(new TokenHandshake(tokens))
                .setAllowedOriginPatterns("*");
    }

    static final class TokenHandshake implements HandshakeInterceptor {
        private final SessionTokens tokens;

        TokenHandshake(SessionTokens tokens) {
            this.tokens = tokens;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String trace = ConnectionDiagnostics.traceId(UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("traceId"));
            attributes.put(GameSocketHandler.CONNECTION_TRACE, trace);
            String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
            var uid = tokens.resolve(token)
                    .or(() -> tokens.fromAuthorization(request.getHeaders().getFirst("Authorization")));
            uid.ifPresent(u -> attributes.put(GameSocketHandler.USER, u));
            if (uid.isEmpty() && token != null && !token.isBlank()) {
                attributes.put(GameSocketHandler.TOKEN_REJECTED, Boolean.TRUE); // 带了令牌但已失效（如后台重启）：连上后立即告知重新登录
            }
            org.slf4j.LoggerFactory.getLogger(WebSocketConfig.class).info("[MN-NET] ws.handshake trace={} authenticated={} tokenPresented={}", trace, uid.isPresent(), token != null || request.getHeaders().containsKey("Authorization"));
            return true; // 未带令牌也允许连上，之后必须先发 AUTH
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            if (exception != null) org.slf4j.LoggerFactory.getLogger(WebSocketConfig.class).warn("[MN-NET] ws.handshake.fail kind={}", ConnectionDiagnostics.failureKind(exception));
        }
    }
}
