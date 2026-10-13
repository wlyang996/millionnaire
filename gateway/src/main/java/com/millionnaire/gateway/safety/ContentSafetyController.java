package com.millionnaire.gateway.safety;
import com.millionnaire.gateway.admin.AdminAccess;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
public class ContentSafetyController {
    private final ContentSafety safety;
    public ContentSafetyController(ContentSafety safety){this.safety=safety;}
    @AdminAccess("dashboard:read") @GetMapping("/admin-api/v1/content-safety")
    public Map<String,Object> status(){return Map.of("enabled",safety.enabled(),"provider","微信云托管开放接口","path","/wxa/msg_sec_check","policy","仅明确通过时允许保存或广播；待审、危险和失败均拒绝","configuration","CONTENT_SAFETY_ENABLED / CONTENT_SAFETY_TIMEOUT_MS");}
}
