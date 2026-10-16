package com.millionnaire.gateway.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.gateway.audio.*;
import com.millionnaire.gateway.characters.*;
import com.millionnaire.gateway.config.*;
import com.millionnaire.gateway.auth.UserStore;
import com.millionnaire.gateway.record.GameRecords;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:adminoperations;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.flyway.placeholders.table_options=","millionnaire.admin.bootstrap-username=operations.root","millionnaire.admin.bootstrap-password=Initial123!","millionnaire.admin.bcrypt-strength=4"})
@ActiveProfiles("db") @DirtiesContext
class AdminOperationsIntegrationTest {
    static class TestClock extends Clock {long now=1900000000000L;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}}
    @TestConfiguration static class Time { @Bean @Primary TestClock operationsClock(){return new TestClock();} }
    @Autowired AdminAuth auth;@Autowired AdminStore store;@Autowired JdbcTemplate db;@Autowired ReleaseWorkflow releases;@Autowired GameConfigs configs;@Autowired TestClock clock;@Autowired UserStore users;@Autowired GameRecords games;@Autowired MediaObjectRegistry registry;@Autowired MediaLibrary media;
    @MockBean AudioObjectStore sounds;@MockBean CharacterObjectStore images;
    @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
    @Autowired AudioCatalog audio;
    private final ObjectMapper json=new ObjectMapper();
    private AdminAuth.Identity root(){var user=store.users().stream().filter(u->u.username().equals("operations.root")).findFirst();if(user.isEmpty()){var r=auth.login("operations.root","Initial123!","fixture","test","init");auth.changePassword(auth.authenticate("Bearer "+r.get("token")),"Initial123!","Changed123!","rotate");}return login("operations.root","Changed123!");}
    private AdminAuth.Identity login(String name,String password){return auth.authenticate("Bearer "+auth.login(name,password,"fixture-"+UUID.randomUUID(),"test","login").get("token"));}
    private AdminAuth.Identity account(AdminAuth.Identity root,String role){String name="test."+UUID.randomUUID().toString().substring(0,8);var made=auth.create(root,name,name,role,"create");String password=(String)made.get("temporaryPassword");var first=login(name,password);auth.changePassword(first,password,"Changed123!","rotate");return login(name,"Changed123!");}
    @Test void customRolesResolveOnLoginCannotGrantSecurityAndChangesRevokeAllSessions(){
        var root=root();var role=auth.saveRole(root,"CUSTOM_HISTORY","战绩客服","查询玩家与战绩",Set.of("player:read","game:read"),null,"role-create");
        var viewer=account(root,role.id());assertThat(auth.me(viewer).get("permissions")).isEqualTo(Set.of("player:read","game:read"));
        assertThatCode(()->AdminAuth.require(viewer,"game:read")).doesNotThrowAnyException();assertThatThrownBy(()->AdminAuth.require(viewer,"config:publish")).isInstanceOf(AdminFault.class);
        String priorToken=(String)auth.login(viewer.user().username(),"Changed123!","role-revocation","test","before-role-change").get("token");
        auth.saveRole(root,role.id(),role.name(),role.description(),Set.of("player:read"),role.version(),"role-update");
        assertThatThrownBy(()->auth.authenticate("Bearer "+priorToken)).isInstanceOf(AdminFault.class);assertThat(store.sessions(viewer.user().id())).allMatch(s->s.revokedAt()!=null);
        assertThat(login(viewer.user().username(),"Changed123!").grants()).containsExactly("player:read");
        assertThatThrownBy(()->auth.saveRole(root,"CUSTOM_ATTACK","非法","",Set.of("admin-user:manage"),null,"attack")).isInstanceOf(AdminFault.class);
        assertThatThrownBy(()->auth.saveRole(root,role.id(),role.name(),"",Set.of(),role.version(),"stale")).isInstanceOf(AdminFault.class);
    }
    @Test void fixedSnapshotsRequireAnotherApproverAndPublishOnlyOnceWhenDue(){
        var root=root();var peer=account(root,"SUPER_ADMIN");long before=configs.activeId();
        var payload=json.valueToTree(Map.of("settings",SettingsMapper.defaults(),"expectedActiveId",before,"note","审批集成测试"));
        var request=releases.submit(root,"config","测试预约发布",payload,clock.millis()+3600000,"submit");
        assertThatThrownBy(()->releases.decide(root,request.id(),1,"approve","","self")).isInstanceOf(AdminFault.class);
        releases.decide(peer,request.id(),1,"approve","","approve");releases.execute(request.id());assertThat(configs.activeId()).isEqualTo(before);
        clock.now+=3600001;releases.execute(request.id());long after=configs.activeId();assertThat(after).isNotEqualTo(before);releases.execute(request.id());assertThat(configs.activeId()).isEqualTo(after);assertThat(releases.detail(root,request.id()).status()).isEqualTo("PUBLISHED");
    }
    @Test void staleVersionsAndRevokedPublishPermissionsBecomeVisibleFailures(){
        var root=root();var peer=account(root,"SUPER_ADMIN");long before=configs.activeId();
        var request=releases.submit(root,"config","过期发布",json.valueToTree(Map.of("settings",SettingsMapper.defaults(),"expectedActiveId",before)),clock.millis()+3600000,"submit-stale");releases.decide(peer,request.id(),1,"approve","","approve-stale");configs.publish(SettingsMapper.defaults(),"并发更新","test",before,null);long changed=configs.activeId();clock.now+=3600001;releases.execute(request.id());assertThat(configs.activeId()).isEqualTo(changed);assertThat(releases.detail(root,request.id()).status()).isEqualTo("FAILED");
        var publisher=account(root,"OPERATOR");var revoked=releases.submit(publisher,"config","权限变化",json.valueToTree(Map.of("settings",SettingsMapper.defaults(),"expectedActiveId",changed)),clock.millis()+3600000,"submit-revoked");releases.decide(peer,revoked.id(),1,"approve","","approve-revoked");var u=store.user(publisher.user().id()).orElseThrow();auth.update(root,u.id(),u.displayName(),"VIEWER","ACTIVE",u.version(),"demote");clock.now+=3600001;releases.execute(revoked.id());assertThat(configs.activeId()).isEqualTo(changed);assertThat(releases.detail(root,revoked.id()).status()).isEqualTo("FAILED");
    }
    @Test void recyclingIsRecoverableVersionedAndPermanentCleanupHasRetentionAndConfirmation(){
        var root=root();String key="audio/test-orphan/"+UUID.randomUUID()+".wav";registry.register(key,"audio",200);
        media.action(root,key,1,"trash","","trash");assertThat(registry.available(key)).isFalse();
        assertThatThrownBy(()->media.action(root,key,2,"purge",key,"early")).isInstanceOf(AdminFault.class);verify(sounds,never()).delete(key);
        media.action(root,key,2,"restore","","restore");assertThat(registry.available(key)).isTrue();
        assertThatThrownBy(()->media.action(root,key,2,"trash","","stale")).isInstanceOf(AdminFault.class);
        media.action(root,key,3,"trash","","trash-again");clock.now+=7L*86400000+1;
        assertThatThrownBy(()->media.action(root,key,4,"purge","wrong","wrong-confirm")).isInstanceOf(AdminFault.class);
        media.action(root,key,4,"purge",key,"purge");verify(sounds).delete(key);assertThat(registry.available(key)).isFalse();
    }
    @Test void playerSearchEscapesWildcardAndNeverExposesWechatIdentity(){
        var a=users.create("客户_百分%号");var b=users.create("客户X百分Y号");
        var found=users.adminPage(1,20,"_百分%");assertThat(found.toString()).contains(Long.toString(a.id())).doesNotContain(Long.toString(b.id()),"open_id","password","token");
        assertThatThrownBy(()->games.adminPage(0,20,"",null,0,Long.MAX_VALUE)).isInstanceOf(AdminFault.class);
    }
    @Test void failedObjectDeletionCannotBeRestoredAndCanBeSafelyRetried(){
        var root=root();String key="audio/purge-failure/"+UUID.randomUUID()+".wav";registry.register(key,"audio",100);media.action(root,key,1,"trash","","trash-failure");clock.now+=7L*86400000+1;
        doThrow(new IllegalStateException("simulated COS failure")).doNothing().when(sounds).delete(key);
        assertThatThrownBy(()->media.action(root,key,2,"purge",key,"failed-purge")).isInstanceOf(AdminFault.class);
        var failed=registry.all().stream().filter(r->r.key().equals(key)).findFirst().orElseThrow();assertThat(failed.status()).isEqualTo("PURGE_FAILED");
        assertThatThrownBy(()->media.action(root,key,failed.version(),"restore","","unsafe-restore")).isInstanceOf(AdminFault.class);
        media.action(root,key,failed.version(),"purge",key,"retry-purge");assertThat(registry.all().stream().filter(r->r.key().equals(key)).findFirst().orElseThrow().status()).isEqualTo("DELETED");verify(sounds,times(2)).delete(key);
    }
    @Test void routesEnforcePermissionsAndNormalizeDatabaseKeys(){
        var root=root();var headers=new org.springframework.http.HttpHeaders();headers.setBearerAuth((String)auth.login(root.user().username(),"Changed123!","http-test","test","login-http").get("token"));
        var u=users.create("接口玩家");var result=http.exchange("/admin-api/v1/players?q="+u.id(),org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(headers),Map.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);assertThat(result.getBody().toString()).contains("userId="+u.id());
        assertThat(http.getForEntity("/admin-api/v1/media",Map.class).getStatusCode().value()).isEqualTo(401);
        var viewer=account(root,"VIEWER");String token=(String)auth.login(viewer.user().username(),"Changed123!","viewer-http","test","viewer-login").get("token");headers.setBearerAuth(token);
        for(String route:List.of("/admin-api/v1/media","/admin-api/v1/releases","/admin-api/v1/roles"))assertThat(http.exchange(route,org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(headers),Map.class).getStatusCode().value()).isEqualTo(403);
    }
    @Test void referencedAudioCannotBeRecycledAndPendingApprovalRetainsOriginalBindings()throws Exception{
        var root=root();byte[] wav;try(var in=getClass().getResourceAsStream("/audio/morning-town-v1.wav")){wav=Objects.requireNonNull(in).readAllBytes();}
        var asset=audio.upload("引用保护测试","music",wav);var old=audio.draft();var scenes=new HashMap<>(old.settings().scenes());scenes.put("lobby",new AudioCatalog.Binding(asset.id(),true));var s=old.settings();var changed=new AudioCatalog.Settings(s.musicEnabled(),s.musicVolume(),s.effectsEnabled(),s.effectsVolume(),s.fadeMs(),scenes,s.effects());
        var draft=audio.save(changed,old.revision(),old.baseVersion());
        assertThatThrownBy(()->media.action(root,asset.key(),1,"trash","","reference-protection")).isInstanceOf(AdminFault.class).hasMessageContaining("引用");
        var request=releases.submit(root,"audio","引用保留审批",json.valueToTree(Map.of("revision",draft.revision(),"expectedVersion",draft.baseVersion())),clock.millis()+3600000,"audio-submit");audio.save(old.settings(),draft.revision(),draft.baseVersion());
        assertThatThrownBy(()->media.action(root,asset.key(),1,"trash","","pending-protection")).isInstanceOf(AdminFault.class);
        releases.decide(root,request.id(),request.version(),"cancel","","cancel-audio");media.action(root,asset.key(),1,"trash","","unused-after-cancel");assertThat(audio.assets()).noneMatch(a->a.id().equals(asset.id()));media.action(root,asset.key(),2,"restore","","restore-audio");assertThat(audio.assets()).anyMatch(a->a.id().equals(asset.id()));
    }
    @Test void historicalGamesRemainQueryableAfterAllPersonalRowsAreRemoved(){
        var user=users.create("历史玩家");var other=users.create("历史玩家二");long room=123450001L;
        games.submit(new GameRecords.Draft(room,1,"TIME_UP","TIME_LIMIT",15,"classic-30",3000,clock.millis()-60000,clock.millis(),"test-config-hash",List.of(new GameRecords.Seat(0,user.id(),1,3500L,3500L,"ALIVE"),new GameRecords.Seat(1,other.id(),2,2500L,2500L,"ALIVE"))));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(()->assertThat(((Number)games.adminPage(1,20,Long.toString(room),user.id(),0,Long.MAX_VALUE).get("total")).longValue()).isEqualTo(1));
        assertThat(games.adminDetail(room,1).orElseThrow().seats()).hasSize(2);
        db.update("DELETE FROM game_record_player WHERE record_id IN (SELECT record_id FROM game_record WHERE room_id=? AND game_no=1)",room);assertThat(games.adminDetail(room,1).orElseThrow().seats()).isEmpty();
        assertThat(games.adminPage(1,20,Long.toString(room),null,0,Long.MAX_VALUE).get("items").toString()).contains("roomId="+room,"configHash=test-config-hash");
    }
    @Test void approvalPreviewCannotFetchAssetsOutsideTheFrozenRequest(){
        var root=root();var draft=audio.draft();var request=releases.submit(root,"audio","审批试听",json.valueToTree(Map.of("revision",draft.revision(),"expectedVersion",draft.baseVersion())),clock.millis()+3600000,"preview-submit");
        assertThat(releases.preview(request,"morning-town")).isNotEmpty();
        assertThatThrownBy(()->releases.preview(request,"not-referenced")).isInstanceOf(AdminFault.class);
        releases.decide(root,request.id(),request.version(),"cancel","","preview-cancel");
    }
}
