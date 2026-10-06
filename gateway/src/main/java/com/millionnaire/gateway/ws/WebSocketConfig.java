package com.millionnaire.gateway.ws;

import com.millionnaire.gateway.auth.SessionTokens;
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
            String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
            tokens.resolve(token)
                    .or(() -> tokens.fromAuthorization(request.getHeaders().getFirst("Authorization")))
                    .ifPresent(uid -> attributes.put(GameSocketHandler.USER, uid));
            return true; // 未带令牌也允许连上，之后必须先发 AUTH
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }
}
