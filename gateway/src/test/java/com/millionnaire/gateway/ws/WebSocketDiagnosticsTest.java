package com.millionnaire.gateway.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.millionnaire.gateway.auth.SessionTokens;
import java.net.URI;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

class WebSocketDiagnosticsTest {
    @Test void traceCorrelationDoesNotChangeTokenValidationOrExposeTheHandshakeQuery() {
        var tokens=new SessionTokens();String token=tokens.issue(123L);
        Logger logger=(Logger)LoggerFactory.getLogger(WebSocketConfig.class);
        var logs=new ListAppender<ILoggingEvent>();logs.start();logger.addAppender(logs);
        try {
            for(boolean valid:new boolean[]{true,false}) {
                String query="token="+(valid?token:"PRIVATE_INVALID_TOKEN")+"&traceId=ws-test-12345";
                var raw=new MockHttpServletRequest("GET","/ws");raw.setQueryString(query);
                var request=new ServletServerHttpRequest(raw){@Override public URI getURI(){return URI.create("http://localhost/ws?"+query);}};
                var attributes=new HashMap<String,Object>();
                assertThat(new WebSocketConfig.TokenHandshake(tokens).beforeHandshake(request,new ServletServerHttpResponse(new MockHttpServletResponse()),mock(WebSocketHandler.class),attributes)).isTrue();
                assertThat(attributes).containsEntry(GameSocketHandler.CONNECTION_TRACE,"ws-test-12345");
                if(valid) assertThat(attributes).containsEntry(GameSocketHandler.USER,123L).doesNotContainKey(GameSocketHandler.TOKEN_REJECTED);
                else assertThat(attributes).containsEntry(GameSocketHandler.TOKEN_REJECTED,true).doesNotContainKey(GameSocketHandler.USER);
            }
            String output=logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",String::concat);
            assertThat(output).contains("ws-test-12345","authenticated=true","authenticated=false").doesNotContain(token,"PRIVATE_INVALID_TOKEN");
        } finally {logger.detachAppender(logs);logs.stop();}
    }
}
