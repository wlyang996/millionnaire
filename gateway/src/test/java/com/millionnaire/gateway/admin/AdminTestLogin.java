package com.millionnaire.gateway.admin;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
public final class AdminTestLogin {
    @SuppressWarnings({"unchecked","rawtypes"})
    public static Map<String,Object> login(TestRestTemplate http,String username,String initial){
        Map<String,Object> login=http.postForObject("/admin-api/v1/auth/login",Map.of("username",username,"password",initial),Map.class);
        String changed=initial+"-changed";
        if(!login.containsKey("token"))login=http.postForObject("/admin-api/v1/auth/login",Map.of("username",username,"password",changed),Map.class);
        if(Boolean.TRUE.equals(login.get("mustChangePassword"))){var headers=new HttpHeaders();headers.setBearerAuth((String)login.get("token"));var result=http.exchange("/admin-api/v1/auth/password",HttpMethod.POST,new HttpEntity<>(Map.of("currentPassword",initial,"newPassword",changed),headers),Map.class);if(!result.getStatusCode().is2xxSuccessful())throw new AssertionError(result);login=http.postForObject("/admin-api/v1/auth/login",Map.of("username",username,"password",changed),Map.class);}
        if(!login.containsKey("token"))throw new AssertionError("Test login failed: "+login.get("code"));return login;
    }
}
