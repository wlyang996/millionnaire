package com.millionnaire.gateway.audio;

import com.millionnaire.gateway.config.AdminController;
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
    public AudioController(AudioCatalog catalog, AdminController admin) { this.catalog = catalog; this.admin = admin; }
    public record Save(AudioCatalog.Settings settings, int revision, long expectedVersion) {}
    public record Publish(int revision, long expectedVersion) {}
    public record Rollback(long version, int revision, long expectedVersion) {}
    @GetMapping("/api/audio") public ResponseEntity<?> client() { return ResponseEntity.ok().header("Cache-Control", "no-store").body(catalog.client()); }
    @GetMapping("/api/audio/assets/{id}.wav") public ResponseEntity<Resource> file(@PathVariable String id) { return wav(catalog.bytes(id, false), true); }
    @GetMapping("/admin-api/v1/audio") public Map<String,Object> state(@RequestHeader(value="Authorization",required=false) String auth) { admin.check(auth); return catalog.admin(); }
    @GetMapping("/admin-api/v1/audio/assets/{id}.wav") public ResponseEntity<Resource> preview(@RequestHeader(value="Authorization",required=false) String auth, @PathVariable String id) { admin.check(auth); return wav(catalog.bytes(id, true), false); }
    @PostMapping(value="/admin-api/v1/audio/assets", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public AudioCatalog.Asset upload(@RequestHeader(value="Authorization",required=false) String auth, @RequestParam String name, @RequestParam String kind, @RequestParam MultipartFile file) throws IOException {
        admin.check(auth); if (file.getSize() > AudioFiles.MAX_BYTES) throw AudioFiles.bad("音频不可超过 10MB");
        return catalog.upload(name,kind,file.getBytes());
    }
    @PostMapping("/admin-api/v1/audio/draft") public AudioCatalog.Draft save(@RequestHeader(value="Authorization",required=false) String auth, @RequestBody Save body) { admin.check(auth); return catalog.save(body.settings(),body.revision(),body.expectedVersion()); }
    @PostMapping("/admin-api/v1/audio/publish") public Map<String,Object> publish(@RequestHeader(value="Authorization",required=false) String auth, @RequestBody Publish body) { admin.check(auth); catalog.publish(body.revision(),body.expectedVersion()); return catalog.admin(); }
    @PostMapping("/admin-api/v1/audio/rollback") public Map<String,Object> rollback(@RequestHeader(value="Authorization",required=false) String auth, @RequestBody Rollback body) { admin.check(auth); catalog.rollback(body.version(),body.expectedVersion(),body.revision()); return catalog.admin(); }
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
