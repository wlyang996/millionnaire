package com.millionnaire.gateway.admin;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:access-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.placeholders.table_options=","millionnaire.admin.bootstrap-username=root.api","millionnaire.admin.bootstrap-password=initial-api-password","millionnaire.admin.bcrypt-strength=4","millionnaire.admin.ip-attempts=1000"})
@ActiveProfiles("db")
@SuppressWarnings({"unchecked","rawtypes"})
class AdminAccessApiTest {
    @Autowired TestRestTemplate http;
    private static final AtomicLong next=new AtomicLong();
    HttpHeaders headers(String token){var h=new HttpHeaders();h.setBearerAuth(token);return h;}
    ResponseEntity<Map> request(HttpMethod method,String url,Object body,String token){return http.exchange("/admin-api/v1"+url,method,new HttpEntity<>(body,headers(token)),Map.class);}
    String admin(){return (String)AdminTestLogin.login(http,"root.api","initial-api-password").get("token");}
    String user(String role,String administrator){String username="api.user"+next.incrementAndGet();var created=request(HttpMethod.POST,"/users",Map.of("username",username,"displayName","测试账号","role",role),administrator);assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);return (String)AdminTestLogin.login(http,username,(String)created.getBody().get("temporaryPassword")).get("token");}
    @Test void anonymousUnknownAndOldSharedPasswordRoutesCannotBypassAuthentication(){
        assertThat(http.getForEntity("/admin-api/v1/config/active",Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity("/admin-api/v1/auth/login",Map.of("password","initial-api-password"),Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(HttpMethod.GET,"/unregistered",null,admin()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void viewerReadsPublishedDataButCannotReadDraftsManageAccountsOrMutate(){
        String token=user("VIEWER",admin());
        for(String path:List.of("/config/active","/config/defaults","/config/history/page","/characters/published","/auth/me"))assertThat(request(HttpMethod.GET,path,null,token).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        for(String path:List.of("/characters","/characters/packs/fake/assets/avatar.png","/users","/roles","/audit"))assertThat(request(HttpMethod.GET,path,null,token).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        for(String path:List.of("/config/validate","/config/publish","/config/rollback","/characters/packs","/characters/publish","/characters/rollback","/characters/configure","/characters/storage/check"))assertThat(request(HttpMethod.POST,path,Map.of(),token).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(HttpMethod.GET,"/characters/published",null,token).getBody()).doesNotContainKeys("packs","storageReady","persistent");
    }
    @Test void operatorPublishesAsOwnUsernameButRollbackIsDeniedAndLogoutRevokes(){
        String administrator=admin(),token=user("OPERATOR",administrator);var me=request(HttpMethod.GET,"/auth/me",null,token).getBody();String username=(String)((Map)me.get("user")).get("username");var current=request(HttpMethod.GET,"/config/active",null,token).getBody();
        var published=request(HttpMethod.POST,"/config/publish",Map.of("settings",current.get("settings"),"expectedActiveId",current.get("configId"),"note","权限回归","createdBy","forged-user"),token);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(published.getBody().get("createdBy")).isEqualTo(username);
        assertThat(request(HttpMethod.POST,"/config/rollback",Map.of("configId",0,"expectedActiveId",published.getBody().get("configId")),token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var audit=request(HttpMethod.GET,"/audit?actor="+username,null,administrator).getBody();assertThat(audit.toString()).contains(username,"publish","DENIED").doesNotContain("forged-user",token);
        assertThat(request(HttpMethod.POST,"/auth/logout",Map.of(),token).getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(request(HttpMethod.GET,"/auth/me",null,token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void initialCredentialOnlyAllowsRotationAndSessionIdsAreOwnershipChecked(){
        String administrator=admin(),username="api.initial"+next.incrementAndGet();var created=request(HttpMethod.POST,"/users",Map.of("username",username,"displayName","初次登录","role","VIEWER"),administrator).getBody();String password=(String)created.get("temporaryPassword");var login=http.postForObject("/admin-api/v1/auth/login",Map.of("username",username,"password",password),Map.class);String token=(String)login.get("token");
        assertThat(request(HttpMethod.GET,"/config/active",null,token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(request(HttpMethod.GET,"/auth/me",null,token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.POST,"/auth/password",Map.of("currentPassword",password,"newPassword","rotated-api-password"),token).getStatusCode()).isEqualTo(HttpStatus.OK);
        var fresh=http.postForObject("/admin-api/v1/auth/login",Map.of("username",username,"password","rotated-api-password"),Map.class);String freshToken=(String)fresh.get("token");String adminSession=(String)request(HttpMethod.GET,"/auth/me",null,administrator).getBody().get("sessionId");
        assertThat(request(HttpMethod.POST,"/auth/sessions/"+adminSession+"/revoke",Map.of(),freshToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(request(HttpMethod.GET,"/users?page=0",null,administrator).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(request(HttpMethod.GET,"/audit?from=100&to=0",null,administrator).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
