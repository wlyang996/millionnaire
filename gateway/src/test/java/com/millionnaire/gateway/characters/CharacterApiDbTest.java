package com.millionnaire.gateway.characters;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.util.Map;
import com.millionnaire.gateway.auth.UserStore;
import com.millionnaire.gateway.room.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="millionnaire.admin.password=character-test-secret")
@ActiveProfiles("db")
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:characters;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.placeholders.table_options="})
class CharacterApiDbTest {
    @TestConfiguration static class Config {
        @Bean @Primary CharacterObjectStore objects() { return new CharacterCatalogTest.Objects(); }
    }
    @Autowired CharacterCatalog catalog;
    @Autowired CharacterObjectStore objects;
    @Autowired Clock clock;
    @Autowired ObjectProvider<JdbcTemplate> jdbc;
    @Autowired TestRestTemplate http;
    @Autowired UserStore users;
    @Autowired RoomService rooms;

    @Test void authenticatedMultipartUploadRequiresAllSevenAndDoesNotLeakDrafts() throws Exception {
        var login = http.postForObject("/admin-api/v1/auth/login",Map.of("password","character-test-secret"),Map.class);
        var h = new HttpHeaders(); h.setBearerAuth((String)login.get("token"));
        var created = http.exchange("/admin-api/v1/characters/packs",HttpMethod.POST,new HttpEntity<>(Map.of("name","HTTP上传角色","sort",9,"enabled",true,"fallback",0),h),CharacterCatalog.Pack.class).getBody();
        String id = created.packId(); int revision = created.revision();
        var partial = http.exchange("/admin-api/v1/characters/publish",HttpMethod.POST,new HttpEntity<>(Map.of("packId",id,"revision",revision,"expectedVersion",catalog.activeId()),h),Map.class);
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (String slot : CharacterImages.SLOTS) {
            var fields = new org.springframework.util.LinkedMultiValueMap<String,Object>();
            fields.add("revision",String.valueOf(revision));
            fields.add("file",new org.springframework.core.io.ByteArrayResource(CharacterCatalogTest.png(true,64,64)) { @Override public String getFilename() { return "pose.png"; } });
            var mh = new HttpHeaders(); mh.setBearerAuth((String)login.get("token")); mh.setContentType(MediaType.MULTIPART_FORM_DATA);
            var result = http.exchange("/admin-api/v1/characters/packs/"+id+"/assets/"+slot,HttpMethod.POST,new HttpEntity<>(fields,mh),CharacterCatalog.Pack.class);
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            revision = result.getBody().revision();
        }
        assertThat(http.getForEntity("/admin-api/v1/characters/packs/"+id+"/assets/avatar.png",byte[].class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/api/characters/assets/"+id+"/avatar.png",byte[].class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var result = http.exchange("/admin-api/v1/characters/publish",HttpMethod.POST,new HttpEntity<>(Map.of("packId",id,"revision",revision,"expectedVersion",catalog.activeId()),h),Map.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        long first = catalog.activeId();
        catalog.configure(created.character().id(),"停用角色",1,false,first);
        assertThat(catalog.selectable(created.character().id())).isFalse();
        assertThat(catalog.load(first).characters().stream().filter(e -> e.id()==created.character().id()).findFirst().orElseThrow().enabled()).isTrue();
        assertThat(http.getForEntity("/api/characters/assets/"+id+"/avatar.png",byte[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
        catalog.rollback(0,catalog.activeId());
    }

    @Test void publishPersistencePermissionsRoomPinningAndRollback() throws Exception {
        assertThat(http.getForEntity("/admin-api/v1/characters",Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        long original = catalog.activeId();
        var p = CharacterCatalogTest.complete(catalog,catalog.create(null,"测试新角色",0,true,1));
        assertThat(http.getForEntity("/api/characters/assets/"+p.packId()+"/avatar.png",byte[].class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> users.create("还未发布",p.character().id())).hasMessageContaining("尚未发布");
        var released = catalog.publish(p.packId(),p.revision(),original);
        var reloaded = new CharacterCatalog(jdbc,objects,clock);
        assertThat(reloaded.activeId()).isEqualTo(released.version());
        assertThat(reloaded.current()).isEqualTo(released);
        var user = users.create("人物测试",p.character().id());
        assertThat(user.avatar()).isEqualTo(p.character().id());
        // Match existing RoomFlowTest's H2-only treatment of VARBINARY LENGTH; production SQL is untouched.
        jdbc.getObject().execute("ALTER TABLE room DROP CONSTRAINT IF EXISTS ck_room_ids");
        jdbc.getObject().execute("ALTER TABLE room ADD CONSTRAINT ck_room_ids CHECK (OCTET_LENGTH(create_request_id) = 16 AND max_saved_game_no >= 0)");
        var room = rooms.create(user,"character-room",null).room();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.readTree(room.snapshot(user.playerId())).get("characterVersion").asLong()).isEqualTo(released.version());
        assertThat(json.readTree(room.snapshot(user.playerId())).path("avatars").path(user.playerId()).asInt()).isEqualTo(user.avatar());
        catalog.rollback(0,catalog.activeId());
        assertThat(json.readTree(room.snapshot(user.playerId())).get("characterVersion").asLong()).isEqualTo(released.version());
        assertThat(reloaded.current().characters()).hasSize(8);
        var image = http.getForEntity("/api/characters/assets/"+p.packId()+"/avatar.png",byte[].class);
        assertThat(image.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(image.getHeaders().getCacheControl()).contains("immutable");
        assertThat(http.getForEntity("/api/characters/versions/"+released.version(),Map.class).getBody()).containsKey("characters");
        var login = http.postForObject("/admin-api/v1/auth/login",Map.of("password","character-test-secret"),Map.class);
        var h = new HttpHeaders(); h.setBearerAuth((String)login.get("token"));
        var stale = http.exchange("/admin-api/v1/characters/rollback",HttpMethod.POST,new HttpEntity<>(Map.of("version",0,"expectedVersion",released.version()),h),Map.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThatThrownBy(() -> users.create("机器人冒名",8)).hasMessageContaining("尚未发布");
    }
}
