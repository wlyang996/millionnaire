package com.millionnaire.gateway.admin;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static com.millionnaire.gateway.admin.AdminWeb.*;

@RestController @RequestMapping("/admin-api/v1/releases")
public class AdminReleaseController {
    private final ReleaseWorkflow releases;
    public AdminReleaseController(ReleaseWorkflow releases){this.releases=releases;}
    public record Submit(String module,String title,JsonNode payload,long scheduledAt){}
    public record Decision(long expectedVersion,String action,String note){}
    @AdminAccess("release:read") @GetMapping public Object page(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="all")String status,HttpServletRequest r){return releases.page(actor(r),page,size,status);}
    @AdminAccess("release:read") @GetMapping("/{id}") public Object detail(@PathVariable String id,HttpServletRequest r){return releases.detail(actor(r),id);}
    @AdminAccess("release:submit") @PostMapping public Object submit(@RequestBody Submit b,HttpServletRequest r){return releases.submit(actor(r),b.module(),b.title(),b.payload(),b.scheduledAt(),requestId(r));}
    @AdminAccess("release:read") @PostMapping("/{id}/decision") public Object decide(@PathVariable String id,@RequestBody Decision b,HttpServletRequest r){return releases.decide(actor(r),id,b.expectedVersion(),b.action(),b.note(),requestId(r));}
    @AdminAccess("release:read") @GetMapping("/{id}/preview/{asset}") public org.springframework.http.ResponseEntity<byte[]> preview(@PathVariable String id,@PathVariable String asset,HttpServletRequest r){var request=releases.detail(actor(r),id);var bytes=releases.preview(request,asset);return org.springframework.http.ResponseEntity.ok().header("Cache-Control","no-store").contentType(org.springframework.http.MediaType.parseMediaType(request.module().equals("audio")?"audio/wav":"image/png")).body(bytes);}
}
