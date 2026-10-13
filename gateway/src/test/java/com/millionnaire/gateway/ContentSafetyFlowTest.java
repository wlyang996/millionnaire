package com.millionnaire.gateway;
import com.millionnaire.gateway.auth.WechatAuth;
import com.millionnaire.gateway.safety.ContentSafety;
import com.millionnaire.gateway.room.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("db")
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:safetyflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.placeholders.table_options=","millionnaire.wechat.app-id=wx-safety","millionnaire.wechat.app-secret=test","millionnaire.content-safety.enabled=true"})
class ContentSafetyFlowTest {
    @LocalServerPort int port;@Autowired TestRestTemplate http;@Autowired JdbcTemplate jdbc;@Autowired RoomService rooms;
    @TestConfiguration static class Fake {
        @Bean WechatAuth.CodeExchanger login(){return (a,s,c)->WechatAuth.Exchange.ok("openid-"+c);}
        @Bean ContentSafety.Checker check(){return (id,text,scene)->{
            assertThat(id).startsWith("openid-");
            if(text.contains("拒绝"))return new ContentSafety.Verdict("RISKY","");
            if(text.contains("待审"))return new ContentSafety.Verdict("REVIEW","");
            if(text.contains("故障"))throw ContentSafety.unavailable();
            if(text.contains("未知"))return new ContentSafety.Verdict("UNKNOWN","");
            return new ContentSafety.Verdict("PASS","trace");
        };}
    }
    @Test void rejectedNicknameNeverWritesAndChatsCheckBeforeBroadcast() throws Exception {
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM app_user",Integer.class);
        var rejected=http.postForEntity("/api/auth/wx-login",Map.of("code","bad","nickname","拒绝名字"),Map.class);
        assertThat(rejected.getStatusCode().is4xxClientError()).isTrue();assertThat(rejected.getBody().get("code")).isEqualTo("CONTENT_REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user",Integer.class)).isEqualTo(before);
        var login=http.postForObject("/api/auth/wx-login",Map.of("code","good","nickname","正常玩家"),Map.class);
        try(var client=new WsClient(port,(String)login.get("token"))) {
            client.awaitType("HELLO");client.call(client.msg("CREATE_ROOM","create"));
            var room=rooms.roomOf((String)login.get("userId")).orElseThrow();
            for(String bad:java.util.List.of("拒绝消息","待审消息","故障消息","未知消息")) {
                var response=client.call(client.msg("CHAT","chat-"+bad).put("text",bad));
                assertThat(response.path("ok").asBoolean()).isFalse();assertThat(room.chatMessage()).doesNotContain(bad);
            }
            var accepted=client.call(client.msg("CHAT","chat-good").put("text","大家好"));
            assertThat(accepted.path("ok").asBoolean()).isTrue();assertThat(room.chatMessage()).contains("大家好");
            assertThat(client.call(client.msg("CHAT","fast").put("text","太快")).path("code").asText()).isEqualTo("TOO_FAST");
        }
        var test=http.postForEntity("/api/auth/test-login",Map.of("nickname","测试身份"),Map.class);
        assertThat(test.getBody().get("code")).isEqualTo("CONTENT_ID_REQUIRED");
    }
}
