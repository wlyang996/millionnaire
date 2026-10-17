package com.millionnaire.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class WechatAuthDiagnosticsTest {
    @Test void providerSuccessErrorAndMalformedResponseRemainTraceableWithoutLeakingSecrets() throws Exception {
        var reply=new AtomicReference<>("{\"openid\":\"PRIVATE_OPENID\",\"session_key\":\"PRIVATE_SESSION\"}");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/sns/jscode2session",exchange->{
            byte[] bytes=reply.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        Logger logger=(Logger)LoggerFactory.getLogger(WechatAuth.class);
        var logs=new ListAppender<ILoggingEvent>();logs.start();logger.addAppender(logs);MDC.put("mnTrace","http-provider-test");
        try {
            var exchanger=WechatAuth.http("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper());
            assertThat(exchanger.exchange("app","PRIVATE_SECRET","PRIVATE_CODE").openid()).isEqualTo("PRIVATE_OPENID");
            reply.set("{\"errcode\":40029,\"errmsg\":\"PRIVATE_PROVIDER_TEXT\"}");
            assertThat(exchanger.exchange("app","PRIVATE_SECRET","PRIVATE_CODE").errcode()).isEqualTo(40029);
            reply.set("PRIVATE_MALFORMED_RESPONSE");
            assertThat(exchanger.exchange("app","PRIVATE_SECRET","PRIVATE_CODE").errcode()).isEqualTo(-1);
            reply.set("null");assertThat(exchanger.exchange("app","PRIVATE_SECRET","PRIVATE_CODE").openid()).isNull();
            String output=logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",String::concat);
            assertThat(output).contains("http-provider-test","wechat.http.start","wechat.http.end","errcode=40029","wechat.http.fail","JsonParseException")
                    .doesNotContain("PRIVATE_SECRET","PRIVATE_CODE","PRIVATE_OPENID","PRIVATE_SESSION","PRIVATE_PROVIDER_TEXT","PRIVATE_MALFORMED_RESPONSE");
        } finally {logger.detachAppender(logs);logs.stop();MDC.remove("mnTrace");server.stop(0);}
    }
}
