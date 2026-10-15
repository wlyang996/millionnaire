package com.millionnaire.gateway.audio;

import static org.assertj.core.api.Assertions.*;
import com.millionnaire.gateway.admin.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:audio-access;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.flyway.placeholders.table_options=",
    "millionnaire.admin.bootstrap-username=root.audioaccess","millionnaire.admin.bootstrap-password=AudioAccess!",
    "millionnaire.admin.bcrypt-strength=4","millionnaire.admin.ip-attempts=1000"})
@ActiveProfiles("db")
@SuppressWarnings({"rawtypes","unchecked"})
class AudioAccessApiTest {
    @TestConfiguration static class Config { @Bean @Primary AudioObjectStore audioObjects(){return new AudioCatalogTest.Objects();} }
    @Autowired TestRestTemplate http;
    @Autowired AudioCatalog catalog;
    @Autowired AdminAuth auth;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired AudioObjectStore objects;
    @Autowired java.time.Clock clock;
    @Autowired AdminPolicy policy;
    private static final AtomicLong next=new AtomicLong();
    String admin(){return (String)AdminTestLogin.login(http,"root.audioaccess","AudioAccess!").get("token");}
    HttpHeaders headers(String token){var h=new HttpHeaders();h.setBearerAuth(token);return h;}
    ResponseEntity<Map> request(HttpMethod method,String path,Object body,String token){return http.exchange("/admin-api/v1"+path,method,new HttpEntity<>(body,headers(token)),Map.class);}
    String user(String role,String administrator){var created=request(HttpMethod.POST,"/users",Map.of("username","audio.user"+next.incrementAndGet(),"displayName","音频测试账号","role",role),administrator).getBody();return (String)AdminTestLogin.login(http,(String)((Map)created.get("user")).get("username"),(String)created.get("temporaryPassword")).get("token");}
    @Test void viewerCannotReadUnpublishedAssetsDraftsOrMutationRoutes(){
        String viewer=user("VIEWER",admin());
        var hidden=catalog.upload("未发布私有资源","music",AudioCatalogTest.wav());
        var published=request(HttpMethod.GET,"/audio/published",null,viewer);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody()).doesNotContainKeys("draft","storageReady","persistent");
        assertThat(published.getBody().toString()).doesNotContain(hidden.id(),hidden.name(),hidden.key(),"key=");
        for(String path:List.of("/audio","/audio/assets/"+hidden.id()+".wav"))
            assertThat(request(HttpMethod.GET,path,null,viewer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.exchange("/admin-api/v1/audio/published/assets/"+hidden.id()+".wav",HttpMethod.GET,new HttpEntity<>(headers(viewer)),byte[].class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for(String path:List.of("/audio/draft","/audio/publish","/audio/rollback"))
            assertThat(request(HttpMethod.POST,path,Map.of(),viewer).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        var uploadHeaders=headers(viewer);uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        var fields=new org.springframework.util.LinkedMultiValueMap<String,Object>();fields.add("name","禁止上传");fields.add("kind","music");
        fields.add("file",new org.springframework.core.io.ByteArrayResource(AudioCatalogTest.wav()){@Override public String getFilename(){return "music.wav";}});
        assertThat(http.exchange("/admin-api/v1/audio/assets",HttpMethod.POST,new HttpEntity<>(fields,uploadHeaders),Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.exchange("/admin-api/v1/audio/published/assets/morning-town.wav",HttpMethod.GET,new HttpEntity<>(headers(viewer)),byte[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void operatorUploadSaveAndPublishAreAuditedAndRollbackRequiresSuperAdmin(){
        String administrator=admin(),operator=user("OPERATOR",administrator);
        String username=auth.authenticate("Bearer "+operator).user().username();
        var fields=new org.springframework.util.LinkedMultiValueMap<String,Object>();fields.add("name","运营上传素材");fields.add("kind","music");
        fields.add("file",new org.springframework.core.io.ByteArrayResource(AudioCatalogTest.wav()){@Override public String getFilename(){return "music.wav";}});
        var uploadHeaders=headers(operator);uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        var uploaded=http.exchange("/admin-api/v1/audio/assets",HttpMethod.POST,new HttpEntity<>(fields,uploadHeaders),AudioCatalog.Asset.class);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        var current=catalog.current();var draft=catalog.draft();var settings=current.settings();var scenes=new HashMap<>(settings.scenes());scenes.put("room",new AudioCatalog.Binding(uploaded.getBody().id(),false));
        var desired=new AudioCatalog.Settings(true,23,settings.effectsEnabled(),settings.effectsVolume(),settings.fadeMs(),scenes,settings.effects());
        var saved=request(HttpMethod.POST,"/audio/draft",new AudioController.Save(desired,draft.revision(),current.version()),operator);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        int revision=((Number)saved.getBody().get("revision")).intValue();
        var published=request(HttpMethod.POST,"/audio/publish",Map.of("revision",revision,"expectedVersion",current.version(),"createdBy","forged-actor"),operator);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        long version=catalog.current().version();
        assertThat(published.getBody().toString()).contains(username).doesNotContain("forged-actor");
        var rollback=new AudioController.Rollback(current.version(),catalog.draft().revision(),version);
        assertThat(request(HttpMethod.POST,"/audio/rollback",rollback,operator).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(HttpMethod.POST,"/audio/rollback",rollback,administrator).getStatusCode()).isEqualTo(HttpStatus.OK);
        var audit=request(HttpMethod.GET,"/audit?actor="+username,null,administrator).getBody().toString();
        assertThat(audit).contains("upload","draft-edit","publish","DENIED",username).doesNotContain(operator,"forged-actor");
    }
    @Test void failedAuditedReleaseRollsBackMetadataAndStaleRevisionDoesNotPublish(){
        var actor=auth.authenticate("Bearer "+admin());var before=catalog.current();var draft=catalog.draft();
        assertThatThrownBy(()->auth.mutation(actor,"audio:publish","audio","publish","",()->{
            catalog.publish(draft.revision(),before.version());throw new IllegalStateException("simulated failure before audit");
        },null,"失败回归","audio-test-request")).isInstanceOf(IllegalStateException.class);
        assertThat(catalog.current()).isEqualTo(before);assertThat(catalog.draft()).isEqualTo(draft);
        catalog.save(before.settings(),draft.revision(),before.version());
        assertThat(request(HttpMethod.POST,"/audio/publish",new AudioController.Publish(draft.revision(),before.version()),admin()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(catalog.current()).isEqualTo(before);
    }
    @Test void separateInstancesCannotBothPublishTheSameReviewedDraft() throws Exception {
        var actor=auth.authenticate("Bearer "+admin());var current=catalog.current();var draft=catalog.draft();
        var peer=new AdminAuth(new AdminStore(()->jdbc),clock,policy);
        var peerCatalog=new AudioCatalog(()->jdbc,objects,clock);
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<String>>();
            for(int i=0;i<2;i++) {
                var instance=i==0?auth:peer;var audio=i==0?catalog:peerCatalog;
                futures.add(pool.submit(()->{
                    start.await();
                    try { instance.mutation(actor,"audio:publish","audio","publish","",()->audio.publish(draft.revision(),current.version()),r->Long.toString(r.version()),"并发发布回归",UUID.randomUUID().toString());return "SUCCESS"; }
                    catch(com.millionnaire.gateway.room.ClientException e){return e.code();}
                }));
            }
            start.countDown();
            var outcomes=new ArrayList<String>();for(var future:futures)outcomes.add(future.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS","STALE_AUDIO");
        } finally {pool.shutdownNow();}
    }
}
