package com.millionnaire.gateway.audio;

import com.millionnaire.gateway.config.AdminController;
import com.millionnaire.gateway.admin.*;
import com.millionnaire.gateway.room.ClientException;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AudioController {
    private final AudioCatalog catalog;
    private final AdminController admin;
    private final AdminAuth authentication;
    public AudioController(AudioCatalog catalog, AdminController admin, AdminAuth authentication) { this.catalog = catalog; this.admin = admin; this.authentication = authentication; }
    public record Save(AudioCatalog.Settings settings, int revision, long expectedVersion) {}
    public record Publish(int revision, long expectedVersion) {}
    public record Rollback(long version, int revision, long expectedVersion) {}
    @GetMapping("/api/audio") public ResponseEntity<?> client() { return ResponseEntity.ok().header("Cache-Control", "no-store").body(catalog.client()); }
    @GetMapping("/api/audio/assets/{id}.wav") public ResponseEntity<Resource> file(@PathVariable String id) { return wav(catalog.bytes(id, false), true); }
    private Map<String,Object> state(boolean published) {
        var result = new java.util.LinkedHashMap<>(published ? catalog.published() : catalog.admin());
        result.put("publishers", authentication.publishers("audio"));
        return result;
    }
    @AdminAccess("audio:read") @GetMapping("/admin-api/v1/audio/published") public Map<String,Object> published() { return state(true); }
    @AdminAccess("audio:read") @GetMapping("/admin-api/v1/audio/versions/{version}")
    public Map<String,Object> version(@PathVariable long version) { return catalog.publishedVersion(version); }
    @AdminAccess("audio:read") @GetMapping("/admin-api/v1/audio/published/assets/{id}.wav") public ResponseEntity<Resource> publishedPreview(@PathVariable String id) { return wav(catalog.bytes(id, false), false); }
    @AdminAccess("audio:draft") @GetMapping("/admin-api/v1/audio") public Map<String,Object> state(@RequestHeader(value="Authorization",required=false) String auth) { admin.check(auth); return state(false); }
    @AdminAccess("audio:draft") @GetMapping("/admin-api/v1/audio/assets/{id}.wav") public ResponseEntity<Resource> preview(@RequestHeader(value="Authorization",required=false) String auth, @PathVariable String id) { admin.check(auth); return wav(catalog.bytes(id, true), false); }
    @AdminAccess("audio:write")
    @PostMapping(value="/admin-api/v1/audio/assets", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public AudioCatalog.Asset upload(@RequestHeader(value="Authorization",required=false) String auth, @RequestParam String name, @RequestParam String kind, @RequestParam MultipartFile file) throws IOException {
        admin.check(auth); if (file.getSize() > AudioFiles.MAX_BYTES) throw AudioFiles.bad("音频不可超过 10MB");
        byte[] bytes=file.getBytes();
        return authentication.mutation(authentication.authenticate(auth),"audio:write","audio","upload","",()->catalog.upload(name,kind,bytes),AudioCatalog.Asset::id,"上传音频资源",AdminWeb.currentRequestId());
    }
    @AdminAccess("audio:write") @PostMapping("/admin-api/v1/audio/draft") public AudioCatalog.Draft save(@RequestHeader(value="Authorization",required=false) String auth, @RequestBody Save body) {
        return authentication.mutation(authentication.authenticate(auth),"audio:write","audio","draft-edit","",()->catalog.save(body.settings(),body.revision(),body.expectedVersion()),d->Integer.toString(d.revision()),"保存音频草稿；参考版本 #"+body.expectedVersion(),AdminWeb.currentRequestId());
    }
    @AdminAccess("audio:publish") @PostMapping("/admin-api/v1/audio/publish") public Map<String,Object> publish(@RequestHeader(value="Authorization",required=false) String auth, @RequestBody Publish body) {
        authentication.mutation(authentication.authenticate(auth),"audio:publish","audio","publish","",()->catalog.publish(body.revision(),body.expectedVersion()),r->Long.toString(r.version()),"发布音频；参考版本 #"+body.expectedVersion(),AdminWeb.currentRequestId());
        return state(false);
    }
    @AdminAccess("audio:rollback") @PostMapping("/admin-api/v1/audio/rollback") public Map<String,Object> rollback(@RequestHeader(value="Authorization",required=false) String auth, @RequestBody Rollback body) {
        authentication.mutation(authentication.authenticate(auth),"audio:rollback","audio","rollback","",()->catalog.rollback(body.version(),body.expectedVersion(),body.revision()),r->Long.toString(r.version()),"回滚音频来源 #"+body.version(),AdminWeb.currentRequestId());
        return state(false);
    }
    private static ResponseEntity<Resource> wav(byte[] data, boolean published) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).contentLength(data.length)
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control",published ? "public, max-age=31536000, immutable" : "no-store")
                .body(new ByteArrayResource(data)); // Spring handles Range requests for Resource responses.
    }
    @ExceptionHandler(AdminController.Unauthorized.class) public ResponseEntity<?> unauthorized() { return ResponseEntity.status(401).body(Map.of("code","UNAUTHENTICATED","message","登录已过期，请重新登录")); }
    @ExceptionHandler(ClientException.class) public ResponseEntity<?> error(ClientException e) {
        int status = e.code().equals("NOT_FOUND") ? 404 : e.code().equals("STALE_AUDIO") ? 409 : e.code().startsWith("COS_") ? 503 : 400;
        return ResponseEntity.status(status).body(Map.of("code",e.code(),"message",e.getMessage()));
    }
}
