package com.millionnaire.gateway.admin;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import com.millionnaire.gateway.config.*;

class AdminSecurityTest {
    @Test void passwordPolicyAcceptsSixThroughTwelveUnicodeCodePointsAndRejectsControls(){
        for(String password:List.of("abcdef","a".repeat(12),"密".repeat(6),"密".repeat(12),"😀".repeat(6),"😀".repeat(12)))
            assertThatCode(()->AdminAuth.validatePassword(password)).doesNotThrowAnyException();
        assertThatThrownBy(()->AdminAuth.validatePassword(null)).isInstanceOf(AdminFault.class);
        for(String password:List.of("","short","a".repeat(13),"密".repeat(13),"😀".repeat(13),"abcde\n","abcde\u0085"))
            assertThatThrownBy(()->AdminAuth.validatePassword(password)).isInstanceOf(AdminFault.class).hasMessageContaining("6～12");
    }
    @Test void bootstrapAcceptsBothPasswordLengthBoundariesAndReportsInvalidConfiguration()throws Exception{
        for(String password:List.of("abcdef","abcdefghijkl")){
            var f=fixture(false);f.policy.setBootstrapPassword(password);
            assertThat(rawLogin(f,"root.test",password)).containsKey("token");
        }
        for(String password:List.of("short","a".repeat(13))){
            var f=fixture(false);f.policy.setBootstrapPassword(password);
            assertThatThrownBy(()->rawLogin(f,"root.test",password)).isInstanceOf(AdminFault.class).hasMessageContaining("6～12");
            assertThat(f.store.users()).isEmpty();
        }
    }
    @Test void existingLongPasswordsStillLoginAndCanRotateToSixCharacters()throws Exception{
        var f=fixture(false);String old="legacy-test-password";
        String hash="{bcrypt}"+new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(old);
        f.store.insert(new AdminStore.User(1,"legacy.test","旧账号",hash,"SUPER_ADMIN","ACTIVE",false,0,0,1,f.clock.now,f.clock.now,0));
        var actor=f.auth.authenticate("Bearer "+rawLogin(f,"legacy.test",old).get("token"));
        f.auth.changePassword(actor,old,"abcdef","rotate-legacy");
        assertThat(rawLogin(f,"legacy.test","abcdef")).containsKey("token");
        assertThatThrownBy(()->rawLogin(f,"legacy.test",old)).isInstanceOf(AdminFault.class);
    }
    @Test void resettingPasswordsCreatesTwelveCharacterCredentialsAndRevokesSessions()throws Exception{
        var f=fixture(false);var admin=administrator(f);
        var created=f.auth.create(admin,"reset.test","测试","VIEWER","create-reset");
        long id=((Number)((Map<?,?>)created.get("user")).get("id")).longValue();
        var oldSession=f.auth.authenticate("Bearer "+rawLogin(f,"reset.test",(String)created.get("temporaryPassword")).get("token"));
        String reset=(String)f.auth.resetPassword(admin,id,f.store.user(id).orElseThrow().version(),"reset").get("temporaryPassword");
        assertThat(reset).hasSize(12);AdminAuth.validatePassword(reset);
        assertThat(f.store.token(oldSession.session().tokenHash()).orElseThrow().revokedAt()).isNotNull();
        assertThat(rawLogin(f,"reset.test",reset)).containsEntry("mustChangePassword",true);
    }
    static class MutableClock extends Clock {long now=Instant.parse("2026-10-13T00:00:00Z").toEpochMilli();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}}
    record Fixture(JdbcTemplate jdbc,AdminStore store,AdminAuth auth,MutableClock clock,AdminPolicy policy){}
    Fixture fixture(boolean db)throws Exception{
        JdbcTemplate jdbc=null;
        if(db){var ds=new DriverManagerDataSource("jdbc:h2:mem:admin-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");jdbc=new JdbcTemplate(ds);try(var in=getClass().getResourceAsStream("/db/migration/V7__admin_accounts_sessions_and_audit.sql");var c=ds.getConnection()){var sql=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8).replace("${table_options}","");ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));}}
        var clock=new MutableClock();var policy=new AdminPolicy();policy.setAllowInMemory(true);policy.setBcryptStrength(4);policy.setBootstrapUsername("root.test");policy.setBootstrapPassword("Initial123!");policy.setIpAttempts(1000);JdbcTemplate database=jdbc;var store=new AdminStore(()->database);return new Fixture(jdbc,store,new AdminAuth(store,clock,policy),clock,policy);
    }
    Map<String,Object> rawLogin(Fixture f,String user,String password){return f.auth.login(user,password,"test-source","Test device",UUID.randomUUID().toString());}
    AdminAuth.Identity administrator(Fixture f){var first=rawLogin(f,"root.test","Initial123!");String token=(String)first.get("token");var actor=f.auth.authenticate("Bearer "+token);assertThat(first.get("mustChangePassword")).isEqualTo(true);assertThatThrownBy(()->AdminAuth.require(actor,"config:publish")).isInstanceOf(AdminFault.class);f.auth.changePassword(actor,"Initial123!","Changed123!","change-request");assertThatThrownBy(()->f.auth.authenticate("Bearer "+token)).isInstanceOf(AdminFault.class);return f.auth.authenticate("Bearer "+rawLogin(f,"root.test","Changed123!").get("token"));}
    @Test void credentialsAndSessionsAreHashedAndRevocationIsSharedAcrossInstances()throws Exception{
        var f=fixture(true);var actor=administrator(f);String token=(String)rawLogin(f,"root.test","Changed123!").get("token");
        assertThat(f.jdbc.queryForList("SELECT password_hash FROM admin_user",String.class)).allMatch(s->s.startsWith("{bcrypt}")&&!s.contains("password"));
        assertThat(f.jdbc.queryForList("SELECT token_hash FROM admin_session",String.class)).allMatch(s->s.length()==64&&!s.equals(token));
        var other=new AdminAuth(new AdminStore(()->f.jdbc),f.clock,f.policy);var session=other.authenticate("Bearer "+token);f.auth.revoke(actor,actor.user().id(),session.session().id(),"revoke-request");
        assertThatThrownBy(()->other.authenticate("Bearer "+token)).isInstanceOf(AdminFault.class);
        String metadata=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(f.auth.me(actor));assertThat(metadata).doesNotContain("passwordHash","tokenHash","Initial123!");
    }
    @Test void rolesStatusAndVersionChangesInvalidateTokensWithoutDeletingAudit()throws Exception{
        var f=fixture(true);var admin=administrator(f);var created=f.auth.create(admin,"ops.test","运营","OPERATOR","create-request");var user=(Map<?,?>)created.get("user");long id=((Number)user.get("id")).longValue();String temp=(String)created.get("temporaryPassword");
        assertThat(temp).hasSize(12);assertThatCode(()->AdminAuth.validatePassword(temp)).doesNotThrowAnyException();
        var limited=f.auth.authenticate("Bearer "+rawLogin(f,"ops.test",temp).get("token"));f.auth.changePassword(limited,temp,"Operator123!","rotation");String token=(String)rawLogin(f,"ops.test","Operator123!").get("token");var operator=f.auth.authenticate("Bearer "+token);
        AdminAuth.require(operator,"config:publish");assertThatThrownBy(()->AdminAuth.require(operator,"config:rollback")).isInstanceOf(AdminFault.class);
        var current=f.store.user(id).orElseThrow();f.auth.update(admin,id,"观察员","VIEWER","ACTIVE",current.version(),"demote");assertThatThrownBy(()->f.auth.authenticate("Bearer "+token)).isInstanceOf(AdminFault.class);
        assertThatThrownBy(()->f.auth.update(admin,id,"过期请求","OPERATOR","ACTIVE",current.version(),"stale")).isInstanceOf(AdminFault.class).extracting(e->((AdminFault)e).code).isEqualTo("STALE_USER");
        var viewer=f.auth.authenticate("Bearer "+rawLogin(f,"ops.test","Operator123!").get("token"));AdminAuth.require(viewer,"config:read");assertThatThrownBy(()->AdminAuth.require(viewer,"character:draft")).isInstanceOf(AdminFault.class);
        assertThatThrownBy(()->f.auth.revoke(viewer,admin.user().id(),null,"foreign-session")).isInstanceOf(AdminFault.class);
        f.auth.update(admin,id,"观察员","VIEWER","DISABLED",f.store.user(id).orElseThrow().version(),"disable");assertThatThrownBy(()->rawLogin(f,"ops.test","Operator123!")).isInstanceOf(AdminFault.class);
        var json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(f.auth.auditPage(1,100,"","","all",0,Long.MAX_VALUE));assertThat(json).contains("ops.test","demote").doesNotContain(temp,"Operator123!","password_hash","token_hash");
    }
    @Test void concurrentDemotionsCannotRemoveTheLastEnabledAdministrator()throws Exception{
        var f=fixture(true);var actor=administrator(f);var second=f.auth.create(actor,"root.second","第二管理员","SUPER_ADMIN","create");long id=((Number)((Map<?,?>)second.get("user")).get("id")).longValue();
        var peer=new AdminAuth(new AdminStore(()->f.jdbc),f.clock,f.policy);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var futures=List.of(pool.submit(()->{start.await();try{f.auth.update(actor,actor.user().id(),"一","VIEWER","ACTIVE",f.store.user(actor.user().id()).orElseThrow().version(),"first");return "OK";}catch(AdminFault e){return e.code;}}),pool.submit(()->{start.await();try{peer.update(actor,id,"二","VIEWER","ACTIVE",1,"second");return "OK";}catch(AdminFault e){return e.code;}}));start.countDown();var results=new ArrayList<String>();for(var future:futures)results.add(future.get(10,TimeUnit.SECONDS));assertThat(results).containsExactlyInAnyOrder("OK","LAST_ADMIN");assertThat(f.store.users().stream().filter(u->u.role().equals("SUPER_ADMIN")&&u.status().equals("ACTIVE")).count()).isEqualTo(1);}
        finally{pool.shutdownNow();}
    }
    @Test void lockExpirySessionExpiryAndSourceLimitsAreEnforced()throws Exception{
        var f=fixture(false);administrator(f);
        for(int i=0;i<5;i++)assertThatThrownBy(()->rawLogin(f,"root.test","wrong-password")).isInstanceOf(AdminFault.class);
        assertThatThrownBy(()->rawLogin(f,"root.test","Changed123!")).isInstanceOf(AdminFault.class).extracting(e->((AdminFault)e).code).isEqualTo("LOCKED");
        f.clock.now+=5*60000L;String token=(String)rawLogin(f,"root.test","Changed123!").get("token");f.clock.now+=12*3600000L;assertThatThrownBy(()->f.auth.authenticate("Bearer "+token)).isInstanceOf(AdminFault.class);
        var limited=fixture(false);limited.policy.setIpAttempts(1);rawLogin(limited,"root.test","Initial123!");assertThatThrownBy(()->rawLogin(limited,"root.test","Initial123!")).isInstanceOf(AdminFault.class).extracting(e->((AdminFault)e).code).isEqualTo("RATE_LIMITED");
    }
    @Test void failedMetadataAndAuditTransactionsDoNotLeavePartiallyPublishedRows()throws Exception{
        var f=fixture(true);var actor=administrator(f);f.jdbc.execute("CREATE TABLE test_metadata(id INT PRIMARY KEY)");
        assertThatThrownBy(()->f.auth.mutation(actor,"config:publish","config","publish","test",()->{f.jdbc.update("INSERT INTO test_metadata VALUES(1)");return "ok";},r->{throw new IllegalStateException("audit target failed");},"safe","request")).isInstanceOf(IllegalStateException.class);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM test_metadata",Long.class)).isZero();
    }
    @Test void staleActivityUpdateCannotResurrectARevokedMemorySession()throws Exception{
        var f=fixture(false);var actor=administrator(f);var stale=actor.session();f.auth.logout(actor,"logout");f.store.touch(stale,f.clock.now+61000);
        assertThat(f.store.token(stale.tokenHash()).orElseThrow().revokedAt()).isNotNull();
    }
    @Test void productionCannotInitializeIntoMemoryAndADatabaseFailureDoesNotFallBack()throws Exception{
        var f=fixture(false);f.policy.setAllowInMemory(false);
        assertThatThrownBy(()->rawLogin(f,"root.test","Initial123!")).isInstanceOf(AdminFault.class).extracting(e->((AdminFault)e).code).isEqualTo("ADMIN_DATABASE_REQUIRED");
        var broken=new AdminAuth(new AdminStore(()->{throw new org.springframework.dao.DataAccessResourceFailureException("test DB unavailable");}),f.clock,f.policy);
        assertThatThrownBy(()->broken.authenticate("Bearer some-long-test-token-value-1234567890")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void initializationIsOneTimePasswordsAreBoundedAndRecoveryRevokesOldSessions()throws Exception{
        var f=fixture(true);var actor=administrator(f);f.policy.setBootstrapPassword("Replace123!");assertThatThrownBy(()->rawLogin(f,"root.test","Replace123!")).isInstanceOf(AdminFault.class);
        assertThatThrownBy(()->AdminAuth.validatePassword("密".repeat(25))).isInstanceOf(AdminFault.class);assertThatThrownBy(()->AdminAuth.username("ADMIN")).isInstanceOf(AdminFault.class);
        f.auth.recover("root.test","Recover123!");assertThatThrownBy(()->f.auth.authenticate("Bearer invalid-token-value-not-real-12345")).isInstanceOf(AdminFault.class);assertThat(f.store.sessions(actor.user().id())).allMatch(s->s.revokedAt()!=null);assertThat(rawLogin(f,"root.test","Recover123!").get("mustChangePassword")).isEqualTo(true);
        var missing=fixture(false);missing.policy.setBootstrapPassword("");assertThatThrownBy(()->rawLogin(missing,"root.test","Initial123!")).isInstanceOf(AdminFault.class).extracting(e->((AdminFault)e).code).isEqualTo("ADMIN_SETUP_REQUIRED");
    }
}
