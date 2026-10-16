package com.millionnaire.gateway.admin;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import static com.millionnaire.gateway.admin.AdminWeb.*;
@RestController @RequestMapping("/admin-api/v1/media")
public class AdminMediaController {
    private final MediaLibrary media;
    public AdminMediaController(MediaLibrary media){this.media=media;}
    public record Action(String key,long expectedVersion,String action,String confirmation){}
    @AdminAccess("media:manage") @GetMapping public Object page(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="all")String status){return media.page(page,size,q.strip(),status);}
    @AdminAccess("media:manage") @PostMapping("/actions") public Object action(@RequestBody Action body,HttpServletRequest r){return media.action(actor(r),body.key(),body.expectedVersion(),body.action(),body.confirmation(),requestId(r));}
}
