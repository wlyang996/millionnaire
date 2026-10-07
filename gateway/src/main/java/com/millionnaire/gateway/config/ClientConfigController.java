package com.millionnaire.gateway.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端显示用的参数（地名、价格与租金表、固定费用），无需登录。UPDATE 消息带房间的 configId，客户端按它拉取。
 * 已发布版本不再修改，可放心缓存。
 */
@RestController
public class ClientConfigController {
    private final GameConfigs configs;

    public ClientConfigController(GameConfigs configs) {
        this.configs = configs;
    }

    @GetMapping("/api/configs/{id}/client")
    public ResponseEntity<Map<String, Object>> client(@PathVariable("id") long id) {
        return configs.load(id).map(p -> {
            GameSettings s = p.settings();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("configId", p.configId());
            out.put("tiers", s.tiers());
            out.put("station", s.station());
            out.put("fees", s.fees());
            out.put("eventCash", s.eventCash());
            out.put("tileNames", s.tileNames());
            return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic()).body(out);
        }).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "NOT_FOUND")));
    }
}
