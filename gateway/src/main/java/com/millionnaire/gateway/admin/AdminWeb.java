package com.millionnaire.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Authentication runs before MVC; mapped handler permissions are explicit and deny by default. */
@Configuration
public class AdminWeb implements WebMvcConfigurer {
    static final String ACTOR="admin.identity",REQUEST_ID="admin.requestId";
    private final AdminAuth auth;
    public AdminWeb(AdminAuth auth){this.auth=auth;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
        @Override public boolean preHandle(HttpServletRequest r,HttpServletResponse response,Object handler){
            if(r.getMethod().equals("POST")&&r.getServletPath().equals("/admin-api/v1/auth/login"))return true;
            if(!(handler instanceof HandlerMethod method))throw new AdminFault(403,"FORBIDDEN","未登记的管理接口不可访问");
            var access=method.getMethodAnnotation(AdminAccess.class);if(access==null)throw new AdminFault(403,"FORBIDDEN","未登记的管理接口不可访问");
            var actor=actor(r);r.setAttribute("admin.permission",access.value());
            if(actor.user().mustChangePassword()&&!access.passwordChangeAllowed())throw new AdminFault(403,"PASSWORD_CHANGE_REQUIRED","请先修改初始密码，再访问后台");
            if(!access.passwordChangeAllowed())AdminAuth.require(actor,access.value());
            return true;
        }
    }).addPathPatterns("/admin-api/**");}
    public static AdminAuth.Identity actor(HttpServletRequest r){var actor=(AdminAuth.Identity)r.getAttribute(ACTOR);if(actor==null)throw AdminAuth.unauthorized();return actor;}
    public static String requestId(HttpServletRequest r){return String.valueOf(r.getAttribute(REQUEST_ID));}
    public static String currentRequestId(){return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes s?requestId(s.getRequest()):UUID.randomUUID().toString();}

    @Component @Order(Ordered.HIGHEST_PRECEDENCE+20)
    public static class Filter extends OncePerRequestFilter {
        private final AdminAuth auth;private final AdminPolicy policy;private final ObjectMapper json;
        public Filter(AdminAuth auth,AdminPolicy policy,ObjectMapper json){this.auth=auth;this.policy=policy;this.json=json;}
        @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getServletPath().startsWith("/admin-api/");}
        @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
            String id=UUID.randomUUID().toString();r.setAttribute(REQUEST_ID,id);response.setHeader("X-Request-Id",id);response.setHeader("Cache-Control","no-store");
            boolean login=r.getMethod().equals("POST")&&r.getServletPath().equals("/admin-api/v1/auth/login");
            try{if(!login)r.setAttribute(ACTOR,auth.authenticate(r.getHeader("Authorization")));chain.doFilter(r,response);}
            catch(AdminFault fault){r.setAttribute("admin.fault",fault.code);response.setStatus(fault.status);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getOutputStream(),body(fault));}
            catch(org.springframework.dao.DataAccessException failure){r.setAttribute("admin.fault","ADMIN_DATABASE_UNAVAILABLE");response.setStatus(503);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getOutputStream(),body(new AdminFault(503,"ADMIN_DATABASE_UNAVAILABLE","后台账号数据库暂时不可用，请稍后重试")));}
            catch(ServletException failure){
                Throwable cause=failure;while(cause.getCause()!=null&&!(cause instanceof org.springframework.dao.DataAccessException))cause=cause.getCause();
                if(cause instanceof org.springframework.dao.DataAccessException){r.setAttribute("admin.fault","ADMIN_DATABASE_UNAVAILABLE");response.setStatus(503);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getOutputStream(),body(new AdminFault(503,"ADMIN_DATABASE_UNAVAILABLE","后台账号数据库暂时不可用，请稍后重试")));}
                else{response.setStatus(500);throw failure;}
            }
            finally{if(!login&&response.getStatus()>=400){try{auth.audit((AdminAuth.Identity)r.getAttribute(ACTOR),"requests",Objects.toString(r.getAttribute("admin.permission"),"request"),r.getServletPath(),response.getStatus()==403?"DENIED":"FAILED","HTTP "+response.getStatus()+" "+Objects.toString(r.getAttribute("admin.fault"),"REQUEST_FAILED"),id);}catch(RuntimeException failure){logger.warn("Failed to persist management failure audit");}}}
        }
        /** Forwarded values only participate if every intervening proxy is configured as trusted. */
        public String source(HttpServletRequest request){ServletRequest raw=request;while(raw instanceof ServletRequestWrapper wrapper)raw=wrapper.getRequest();String peer=raw.getRemoteAddr();if(!policy.getTrustedProxies().contains(peer))return peer;String forwarded=request.getHeader("X-Forwarded-For");if(forwarded==null||forwarded.length()>500)return peer;String[] chain=forwarded.split(",");for(int i=chain.length-1;i>=0;i--){String ip=chain[i].strip();if(!ip.matches("[0-9a-fA-F:.]{3,45}"))return peer;if(!policy.getTrustedProxies().contains(ip))return ip;}return peer;}
    }
    public static Map<String,Object> body(AdminFault f){var out=new LinkedHashMap<String,Object>();out.put("code",f.code);out.put("message",f.getMessage());if(f.retryAt!=null)out.put("retryAt",f.retryAt);return out;}
    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(AdminFault.class)public ResponseEntity<?> fault(AdminFault f,HttpServletRequest r){r.setAttribute("admin.fault",f.code);return ResponseEntity.status(f.status).body(body(f));}
    }
}
