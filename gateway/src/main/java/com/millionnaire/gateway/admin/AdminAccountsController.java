package com.millionnaire.gateway.admin;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.millionnaire.gateway.admin.AdminWeb.*;

@RestController @RequestMapping("/admin-api/v1")
public class AdminAccountsController {
    private final AdminAuth auth;private final AdminWeb.Filter filter;
    public AdminAccountsController(AdminAuth auth,AdminWeb.Filter filter){this.auth=auth;this.filter=filter;}
    public record Login(String username,String password){}
    public record Password(String currentPassword,String newPassword){}
    public record Create(String username,String displayName,String role){}
    public record Update(String displayName,String role,String status,Long expectedVersion){}
    public record Expected(Long expectedVersion){}
    @PostMapping("/auth/login") public Map<String,Object> login(@RequestBody(required=false)Login body,HttpServletRequest r){return auth.login(body==null?null:body.username(),body==null?null:body.password(),filter.source(r),r.getHeader("User-Agent"),requestId(r));}
    @AdminAccess(passwordChangeAllowed=true) @GetMapping("/auth/me") public Map<String,Object> me(HttpServletRequest r){return auth.me(actor(r));}
    @AdminAccess(passwordChangeAllowed=true) @PostMapping("/auth/logout") public Map<String,Object> logout(HttpServletRequest r){auth.logout(actor(r),requestId(r));return Map.of("ok",true);}
    @AdminAccess(passwordChangeAllowed=true) @PostMapping("/auth/password") public Map<String,Object> password(@RequestBody Password body,HttpServletRequest r){auth.changePassword(actor(r),body.currentPassword(),body.newPassword(),requestId(r));return Map.of("ok",true,"loginRequired",true);}
    @AdminAccess @GetMapping("/auth/sessions") public Object sessions(HttpServletRequest r){return auth.sessions(actor(r),actor(r).user().id());}
    @AdminAccess @PostMapping("/auth/sessions/{id}/revoke") public Map<String,Object> revoke(@PathVariable String id,HttpServletRequest r){auth.revoke(actor(r),actor(r).user().id(),id,requestId(r));return Map.of("ok",true);}
    @AdminAccess("admin-user:manage") @GetMapping("/users") public Object users(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="all")String status){return auth.users(page,size,q.strip(),status);}
    @AdminAccess("admin-user:manage") @PostMapping("/users") public Object create(@RequestBody Create body,HttpServletRequest r){return auth.create(actor(r),body.username(),body.displayName(),body.role(),requestId(r));}
    @AdminAccess("admin-user:manage") @PatchMapping("/users/{id}") public Object update(@PathVariable long id,@RequestBody Update body,HttpServletRequest r){if(body.expectedVersion()==null)throw new AdminFault(400,"BAD_REQUEST","缺少账号版本");return auth.update(actor(r),id,body.displayName(),body.role(),body.status(),body.expectedVersion(),requestId(r));}
    @AdminAccess("admin-user:manage") @PostMapping("/users/{id}/reset-password") public Object reset(@PathVariable long id,@RequestBody Expected body,HttpServletRequest r){if(body.expectedVersion()==null)throw new AdminFault(400,"BAD_REQUEST","缺少账号版本");return auth.resetPassword(actor(r),id,body.expectedVersion(),requestId(r));}
    @AdminAccess("admin-session:manage") @GetMapping("/users/{id}/sessions") public Object userSessions(@PathVariable long id,HttpServletRequest r){return auth.sessions(actor(r),id);}
    @AdminAccess("admin-session:manage") @PostMapping("/users/{id}/sessions/revoke-all") public Object userRevoke(@PathVariable long id,HttpServletRequest r){auth.revoke(actor(r),id,null,requestId(r));return Map.of("ok",true);}
    @AdminAccess("admin-user:manage") @GetMapping("/roles") public Object roles(){return List.of(role("SUPER_ADMIN","超级管理员","全部内容、账号、会话和审计权限"),role("OPERATOR","运营管理员","编辑、上传及发布；不能回滚或管理账号"),role("VIEWER","只读观察员","查看统计、生效内容及历史；不能读取草稿"));}
    private Object role(String id,String name,String description){return Map.of("id",id,"name",name,"description",description,"permissions",AdminAuth.ROLES.get(id));}
    @AdminAccess("audit:read") @GetMapping("/audit") public Object audit(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="")String actor,@RequestParam(defaultValue="")String action,@RequestParam(defaultValue="all")String result,@RequestParam(defaultValue="0")long from,@RequestParam(defaultValue="9223372036854775807")long to){return auth.auditPage(page,size,actor,action,result,from,to);}
}
