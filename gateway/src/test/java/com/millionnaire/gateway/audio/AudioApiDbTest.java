package com.millionnaire.gateway.audio;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="millionnaire.admin.password=audio-test-secret")
@ActiveProfiles("db")
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:audio;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.placeholders.table_options="})
class AudioApiDbTest {
    @TestConfiguration static class Config { @Bean @Primary AudioObjectStore audioObjects() { return new AudioCatalogTest.Objects(); } }
    @Autowired AudioCatalog catalog;
    @Autowired AudioObjectStore objects;
    @Autowired Clock clock;
    @Autowired ObjectProvider<JdbcTemplate> jdbc;
    @Autowired TestRestTemplate http;
    @Test void persistentDraftAuthenticatedUploadPublishRollbackAndHttpRange() {
        assertThat(http.getForEntity("/admin-api/v1/audio",Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var login = http.postForObject("/admin-api/v1/auth/login",Map.of("password","audio-test-secret"),Map.class);
        var headers = new HttpHeaders(); headers.setBearerAuth((String) login.get("token")); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var fields = new org.springframework.util.LinkedMultiValueMap<String,Object>(); fields.add("name","后台测试"); fields.add("kind","music");
        fields.add("file",new org.springframework.core.io.ByteArrayResource(AudioCatalogTest.wav()) { @Override public String getFilename() { return "music.wav"; } });
        var upload = http.exchange("/admin-api/v1/audio/assets",HttpMethod.POST,new HttpEntity<>(fields,headers),AudioCatalog.Asset.class);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.OK); String id = upload.getBody().id();
        assertThat(http.getForEntity("/api/audio/assets/"+id+".wav",byte[].class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var s = catalog.current().settings(); var scenes = new HashMap<>(s.scenes()); scenes.put("room",new AudioCatalog.Binding(id,false));
        var d = catalog.save(new AudioCatalog.Settings(true,20,false,65,400,scenes,s.effects()),catalog.draft().revision(),catalog.current().version());
        var reloaded = new AudioCatalog(jdbc,objects,clock); assertThat(reloaded.draft()).isEqualTo(d); assertThat(reloaded.current().version()).isZero();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var publish = http.exchange("/admin-api/v1/audio/publish",HttpMethod.POST,new HttpEntity<>(new AudioController.Publish(d.revision(),0),headers),Map.class);
        assertThat(publish.getStatusCode()).isEqualTo(HttpStatus.OK); long version = catalog.current().version();
        assertThat(reloaded.current()).isEqualTo(catalog.current());
        var ranges = new HttpHeaders(); ranges.set("Range","bytes=0-43");
        var partial = http.exchange("/api/audio/assets/"+id+".wav",HttpMethod.GET,new HttpEntity<>(ranges),byte[].class);
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT); assertThat(partial.getBody()).hasSize(44);
        assertThat(partial.getHeaders().getContentType().toString()).isEqualTo("audio/wav"); assertThat(partial.getHeaders().getCacheControl()).contains("immutable");
        var stale = http.exchange("/admin-api/v1/audio/draft",HttpMethod.POST,new HttpEntity<>(new AudioController.Save(s,d.revision(),0),headers),Map.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        catalog.rollback(0,version,catalog.draft().revision());
        assertThat(reloaded.current().settings()).isEqualTo(AudioCatalog.defaults()); assertThat(catalog.load(version).settings().musicVolume()).isEqualTo(20);
        assertThat(http.getForEntity("/api/audio/assets/"+id+".wav",byte[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/audio",Map.class).getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
