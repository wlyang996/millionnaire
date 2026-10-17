package com.millionnaire.gateway.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConnectionDiagnosticsTest {
    @Test void correlatesRequestsAndNeverLogsCredentialsOrQueryStrings() throws Exception {
        Logger logger=(Logger)LoggerFactory.getLogger(ConnectionDiagnostics.class);
        var logs=new ListAppender<ILoggingEvent>();logs.start();logger.addAppender(logs);
        try {
            var request=new MockHttpServletRequest("POST","/api/auth/wx-login");request.setServletPath("/api/auth/wx-login");
            request.addHeader(ConnectionDiagnostics.HEADER,"http-test-12345");request.addHeader("Authorization","Bearer PRIVATE_TOKEN");
            request.setQueryString("secret=PRIVATE_SECRET");request.setContent("{\"code\":\"PRIVATE_CODE\"}".getBytes());
            var response=new MockHttpServletResponse();
            new ConnectionDiagnostics().doFilter(request,response,(req,res)->{
                assertThat(ConnectionDiagnostics.trace()).isEqualTo("http-test-12345");
                ((jakarta.servlet.http.HttpServletResponse)res).setStatus(401);
            });
            assertThat(response.getHeader(ConnectionDiagnostics.HEADER)).isEqualTo("http-test-12345");
            assertThat(ConnectionDiagnostics.trace()).isNull();
            String output=logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",String::concat);
            assertThat(output).contains("http.start","http.end","http-test-12345","status=401").doesNotContain("PRIVATE_TOKEN","PRIVATE_SECRET","PRIVATE_CODE");
        } finally {logger.detachAppender(logs);logs.stop();MDC.remove("mnTrace");}
    }

    @Test void failuresRestoreMdcAndRejectUntrustedTraceValues() throws Exception {
        assertThat(ConnectionDiagnostics.traceId("bad\r\ntrace=PRIVATE")).startsWith("server-");
        MDC.put("mnTrace","outer");
        var request=new MockHttpServletRequest("GET","/api/boards");request.setServletPath("/api/boards");
        try {
            assertThatThrownBy(()->new ConnectionDiagnostics().doFilter(request,new MockHttpServletResponse(),(req,res)->{throw new ServletException("PRIVATE_EXCEPTION");})).isInstanceOf(ServletException.class);
            assertThat(ConnectionDiagnostics.trace()).isEqualTo("outer");
        } finally {MDC.remove("mnTrace");}
    }
}
