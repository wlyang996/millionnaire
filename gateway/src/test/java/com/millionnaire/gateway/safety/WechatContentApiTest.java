package com.millionnaire.gateway.safety;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
class WechatContentApiTest {
    @Test void requestUsesVersionTwoTrustedIdentityAndStrictVerdicts() throws Exception {
        var body=new AtomicReference<>("{\"errcode\":0,\"result\":{\"suggest\":\"pass\"},\"trace_id\":\"test-trace\"}");
        var received=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/wxa/msg_sec_check",x->{received.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] data=body.get().getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);x.close();});server.start();
        try {
            var api=new WechatContentApi(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/wxa/msg_sec_check"),1000);
            assertThat(api.check("trusted-openid","你好",2).status()).isEqualTo("PASS");
            var sent=new ObjectMapper().readTree(received.get());assertThat(sent.path("version").asInt()).isEqualTo(2);assertThat(sent.path("openid").asText()).isEqualTo("trusted-openid");assertThat(sent.path("content").asText()).isEqualTo("你好");
            body.set("{\"errcode\":0,\"result\":{\"suggest\":\"review\"}}");assertThat(api.check("id","text",1).status()).isEqualTo("REVIEW");
            body.set("{\"errcode\":0,\"result\":{\"suggest\":\"risky\"}}");assertThat(api.check("id","text",1).status()).isEqualTo("RISKY");
            body.set("{\"errcode\":40001,\"errmsg\":\"provider secret\"}");assertThatThrownBy(()->api.check("id","private text",2)).hasMessageNotContaining("secret").hasMessageContaining("暂不可用");
            body.set("x".repeat(40000));assertThatThrownBy(()->api.check("id","text",2)).hasMessageContaining("暂不可用");
        } finally {server.stop(0);}
    }
}
