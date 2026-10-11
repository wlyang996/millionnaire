package com.millionnaire.gateway.characters;

import com.millionnaire.gateway.config.AdminController;
import com.millionnaire.gateway.room.ClientException;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class CharacterController {
    private final CharacterCatalog catalog;
    private final AdminController admin;
    public CharacterController(CharacterCatalog catalog, AdminController admin) { this.catalog = catalog; this.admin = admin; }
    public record Draft(Integer avatar, String name, int sort, boolean enabled, int fallback, Integer revision) {}
    public record Publish(String packId, int revision, long expectedVersion) {}
    public record Rollback(long version, long expectedVersion) {}
    public record Configure(int id, String name, int sort, boolean enabled, long expectedVersion) {}

    @GetMapping("/api/characters") public Map<String,Object> active() { return catalog.client(catalog.current()); }
    @GetMapping("/api/characters/versions/{version}") public Map<String,Object> version(@PathVariable long version) { return catalog.client(catalog.load(version)); }
    @GetMapping("/api/characters/assets/{packId}/{slot}.png")
    public ResponseEntity<byte[]> image(@PathVariable String packId, @PathVariable String slot) { return png(catalog.image(packId, slot, false), true); }

    @GetMapping("/admin-api/v1/characters")
    public Map<String,Object> list(@RequestHeader(value="Authorization", required=false) String auth) {
        admin.check(auth);
        return Map.of("active", catalog.client(catalog.current()), "packs", catalog.packs(), "history", catalog.history().stream().map(CharacterCatalog.Catalog::version).toList(),
                "storageReady", catalog.storageReady(), "persistent", catalog.persistent(), "slots", CharacterImages.SLOTS);
    }
    @PostMapping("/admin-api/v1/characters/packs")
    public CharacterCatalog.Pack create(@RequestHeader(value="Authorization", required=false) String auth, @RequestBody Draft body) {
        admin.check(auth); return catalog.create(body.avatar(), body.name(), body.sort(), body.enabled(), body.fallback());
    }
    @PostMapping("/admin-api/v1/characters/storage/check")
    public Map<String,Object> checkStorage(@RequestHeader(value="Authorization", required=false) String auth) {
        admin.check(auth); catalog.verifyStorage();
        return Map.of("ok", true, "message", "已取得微信云托管临时凭证；实际读写权限请通过上传素材验证");
    }
    @PostMapping("/admin-api/v1/characters/packs/{id}")
    public CharacterCatalog.Pack edit(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @RequestBody Draft body) {
        admin.check(auth);
        if (body.revision() == null) throw new ClientException("INVALID_CHARACTER", "缺少草稿版本");
        return catalog.edit(id, body.revision(), body.name(), body.sort(), body.enabled(), body.fallback());
    }
    @PostMapping(value="/admin-api/v1/characters/packs/{id}/assets/{slot}", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public CharacterCatalog.Pack upload(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id,
                                        @PathVariable String slot, @RequestParam int revision, @RequestParam MultipartFile file) throws IOException {
        admin.check(auth);
        if (file.getSize() > CharacterImages.MAX_BYTES) throw new ClientException("INVALID_IMAGE", "图片不可超过 5MB");
        return catalog.upload(id, slot, revision, file.getBytes());
    }
    @GetMapping("/admin-api/v1/characters/packs/{id}/assets/{slot}.png")
    public ResponseEntity<byte[]> preview(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable String id, @PathVariable String slot) {
        admin.check(auth); return png(catalog.image(id, slot, true), false);
    }
    @PostMapping("/admin-api/v1/characters/publish")
    public Map<String,Object> publish(@RequestHeader(value="Authorization", required=false) String auth, @RequestBody Publish body) {
        admin.check(auth); return catalog.client(catalog.publish(body.packId(), body.revision(), body.expectedVersion()));
    }
    @PostMapping("/admin-api/v1/characters/rollback")
    public Map<String,Object> rollback(@RequestHeader(value="Authorization", required=false) String auth, @RequestBody Rollback body) {
        admin.check(auth); return catalog.client(catalog.rollback(body.version(), body.expectedVersion()));
    }
    @PostMapping("/admin-api/v1/characters/configure")
    public Map<String,Object> configure(@RequestHeader(value="Authorization", required=false) String auth, @RequestBody Configure body) {
        admin.check(auth); return catalog.client(catalog.configure(body.id(),body.name(),body.sort(),body.enabled(),body.expectedVersion()));
    }
    private static ResponseEntity<byte[]> png(byte[] bytes, boolean published) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", published ? "public, max-age=31536000, immutable" : "no-store").body(bytes);
    }
    @ExceptionHandler(AdminController.Unauthorized.class)
    public ResponseEntity<?> unauthenticated() { return ResponseEntity.status(401).body(Map.of("code","UNAUTHENTICATED","message","登录已过期，请重新登录")); }
    @ExceptionHandler(ClientException.class)
    public ResponseEntity<?> error(ClientException e) {
        int status = e.code().equals("NOT_FOUND") ? 404 : e.code().equals("STALE_CATALOG") ? 409 : e.code().startsWith("COS_") ? 503 : 400;
        return ResponseEntity.status(status).body(Map.of("code",e.code(),"message",e.getMessage()));
    }
}
