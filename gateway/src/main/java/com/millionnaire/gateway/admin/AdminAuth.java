package com.millionnaire.gateway.admin;

import com.millionnaire.gateway.auth.Ids;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import static com.millionnaire.gateway.admin.AdminStore.*;

@Service
public class AdminAuth {
    public record Identity(User user,Session session) {}
    private record Outcome(Map<String,Object> value,AdminFault error) {}
    private final AdminStore store; private final Clock clock; private final AdminPolicy policy;
    private final BCryptPasswordEncoder passwords; private final String dummy;
    private final SecureRandom random=new SecureRandom();
    public static final Map<String,Set<String>> ROLES;
    static {
        var read=Set.of("dashboard:read","config:read","character:read","audio:read");
        var operator=new HashSet<>(read);operator.addAll(Set.of("config:write","config:publish","character:draft","character:write","character:publish","audio:draft","audio:write","audio:publish"));
        var admin=new HashSet<>(operator);admin.addAll(Set.of("config:rollback","character:rollback","audio:rollback","admin-user:manage","admin-session:manage","audit:read"));
        ROLES=Map.of("SUPER_ADMIN",Set.copyOf(admin),"OPERATOR",Set.copyOf(operator),"VIEWER",read);
    }
    public AdminAuth(AdminStore store,Clock clock,AdminPolicy policy){this.store=store;this.clock=clock;this.policy=policy;policy.validate();passwords=new BCryptPasswordEncoder(policy.getBcryptStrength());dummy=passwords.encode(UUID.randomUUID().toString());}
    public static Map<String,Object> publicUser(User u){return Map.of("id",u.id(),"username",u.username(),"displayName",u.displayName(),"role",u.role(),"status",u.status(),"mustChangePassword",u.mustChangePassword(),"version",u.version(),"lastLoginAt",u.lastLoginAt(),"lockedUntil",u.lockedUntil());}
    public Map<String,Object> me(Identity actor){return Map.of("user",publicUser(actor.user()),"permissions",permissions(actor.user()),"expiresAt",actor.session().expiresAt(),"sessionId",actor.session().id(),"mustChangePassword",actor.user().mustChangePassword(),"persistent",store.persistent());}
    public Map<String,Object> publishers(String module){return store.publishers(module);}
    public static Set<String> permissions(User u){return ROLES.getOrDefault(u.role(),Set.of());}
    public static void require(Identity actor,String permission){if(actor==null)throw unauthorized();if(actor.user().mustChangePassword())throw new AdminFault(403,"PASSWORD_CHANGE_REQUIRED","首次登录或密码已重置，请先修改密码");if(!permission.equals("self")&&!permissions(actor.user()).contains(permission))throw new AdminFault(403,"FORBIDDEN","当前角色没有此操作权限");}
    public Identity authenticate(String authorization){
        if(authorization==null||!authorization.startsWith("Bearer "))throw unauthorized();String token=authorization.substring(7).strip();if(token.length()<32||token.length()>128)throw unauthorized();
        var session=store.token(hash(token)).orElseThrow(AdminAuth::unauthorized);long now=clock.millis();if(session.revokedAt()!=null||session.expiresAt()<=now)throw unauthorized();
        var user=store.user(session.userId()).orElseThrow(AdminAuth::unauthorized);if(!user.status().equals("ACTIVE"))throw unauthorized();store.touch(session,now);return new Identity(user,session);
    }
    private void initialize(){
        if(!store.persistent()&&!policy.isAllowInMemory())throw new AdminFault(503,"ADMIN_DATABASE_REQUIRED","后台账号需要数据库；仅本地开发可设置 ADMIN_ALLOW_IN_MEMORY=true");
        if(!store.users().isEmpty())return;
        if(policy.getBootstrapUsername().isBlank()||policy.getBootstrapPassword().isBlank())throw new AdminFault(503,"ADMIN_SETUP_REQUIRED","后台账号尚未初始化，请配置 ADMIN_BOOTSTRAP_USERNAME 和 ADMIN_BOOTSTRAP_PASSWORD");
        String name;try{name=username(policy.getBootstrapUsername());validatePassword(policy.getBootstrapPassword());}catch(AdminFault e){throw new AdminFault(503,"ADMIN_SETUP_REQUIRED","初始化账号或密码不符合要求，请检查后台初始化配置");}
        long now=clock.millis();var user=new User(Ids.nextId(),name,"超级管理员",encode(policy.getBootstrapPassword()),"SUPER_ADMIN","ACTIVE",true,0,0,1,now,now,0);store.insert(user);
        audit(null,"security","bootstrap",name,"SUCCESS","首次初始化超级管理员",UUID.randomUUID().toString());
        policy.setBootstrapPassword("");
    }
    public Map<String,Object> login(String username,String password,String source,String device,String requestId){
        Outcome outcome=store.atomic(()->{
            initialize();long now=clock.millis();String key=hash(source);Rate rate=store.rate(key);long window=policy.getIpWindowSeconds()*1000L;
            if(rate==null||now-rate.windowStarted()>=window)rate=new Rate(now,0);
            if(rate.attempts()>=policy.getIpAttempts()){audit(null,"security","login","","DENIED","来源登录频率过高",requestId);return new Outcome(null,new AdminFault(429,"RATE_LIMITED","登录过于频繁，请稍后再试",rate.windowStarted()+window));}
            store.rate(key,new Rate(rate.windowStarted(),rate.attempts()+1));
            String name=username==null?"":username.strip().toLowerCase(Locale.ROOT);User user=store.user(name).orElse(null);
            if(user!=null&&user.status().equals("ACTIVE")&&now<user.lockedUntil()){audit(new Identity(user,null),"security","login",name,"DENIED","账号暂时锁定",requestId);return new Outcome(null,new AdminFault(429,"LOCKED","账号暂时锁定，请稍后再试",user.lockedUntil()));}
            boolean valid=password!=null&&password.getBytes(StandardCharsets.UTF_8).length<=72&&passwords.matches(password,user==null?dummy:rawHash(user.passwordHash()));
            if(!valid||user==null||!user.status().equals("ACTIVE")){
                if(user!=null&&user.status().equals("ACTIVE")){int failures=now>=user.lockedUntil()&&user.lockedUntil()>0?1:user.failures()+1;long locked=failures>=policy.getMaxFailures()?now+policy.getLockMinutes()*60000L:0;store.save(copy(user,user.displayName(),user.passwordHash(),user.role(),user.status(),user.mustChangePassword(),locked>0?0:failures,locked,user.version(),now,user.lastLoginAt()));}
                audit(user==null?null:new Identity(user,null),"security","login",user==null?"":user.username(),"FAILED","账号或密码不正确",requestId);return new Outcome(null,new AdminFault(403,"BAD_CREDENTIALS","账号或密码不正确"));
            }
            var current=copy(user,user.displayName(),user.passwordHash(),user.role(),user.status(),user.mustChangePassword(),0,0,user.version(),now,now);store.save(current);
            // Keep the active device list bounded without relying on browser logout.
            store.sessions(user.id()).stream().filter(s->s.revokedAt()==null&&s.expiresAt()>now).sorted(Comparator.comparingLong(Session::createdAt).reversed()).skip(19).forEach(s->store.revoke(current.id(),s.id(),now));
            byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            var session=new Session(UUID.randomUUID().toString(),hash(token),user.id(),now,now+policy.getSessionHours()*3600000L,null,now,safe(device,160));store.issue(session);
            audit(new Identity(current,session),"security","login",session.id(),"SUCCESS","登录成功",requestId);
            var result=new LinkedHashMap<String,Object>(me(new Identity(current,session)));result.put("token",token);return new Outcome(result,null);
        });
        if(outcome.error()!=null)throw outcome.error();return outcome.value();
    }
    public void logout(Identity actor,String requestId){store.atomic(()->{store.revoke(actor.user().id(),actor.session().id(),clock.millis());audit(actor,"security","logout",actor.session().id(),"SUCCESS","退出当前会话",requestId);return null;});}
    public void changePassword(Identity actor,String currentPassword,String newPassword,String requestId){validatePassword(newPassword);store.atomic(()->{var u=existing(actor.user().id());if(currentPassword==null||currentPassword.getBytes(StandardCharsets.UTF_8).length>72||!passwords.matches(currentPassword,rawHash(u.passwordHash())))throw new AdminFault(403,"BAD_CREDENTIALS","当前密码不正确");if(passwords.matches(newPassword,rawHash(u.passwordHash())))throw bad("新密码不能与当前密码相同");store.save(copy(u,u.displayName(),encode(newPassword),u.role(),u.status(),false,0,0,u.version()+1,clock.millis(),u.lastLoginAt()));store.revoke(u.id(),null,clock.millis());audit(actor,"security","password-change",u.username(),"SUCCESS","本人修改密码，撤销全部会话",requestId);return null;});}
    public Map<String,Object> users(int page,int size,String q,String status){page(page,size);if(q.length()>100||!Set.of("all","ACTIVE","DISABLED").contains(status))throw bad("账号筛选参数不正确");var items=store.users().stream().filter(u->(status.equals("all")||status.equals(u.status()))&&(u.username()+" "+u.displayName()).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))).sorted(Comparator.comparingLong(User::createdAt).thenComparing(User::id).reversed()).toList();return Map.of("items",items.stream().skip((long)(page-1)*size).limit(size).map(AdminAuth::publicUser).toList(),"total",items.size(),"page",page,"size",size);}
    public Map<String,Object> create(Identity actor,String name,String display,String role,String requestId){require(actor,"admin-user:manage");String normalized=username(name);display=displayName(display);validRole(role);String shown=display;return store.atomic(()->{if(store.user(normalized).isPresent())throw new AdminFault(409,"USERNAME_EXISTS","账号已存在");String password=temporaryPassword();long now=clock.millis();var user=new User(Ids.nextId(),normalized,shown,encode(password),role,"ACTIVE",true,0,0,1,now,now,0);store.insert(user);audit(actor,"accounts","user-create",normalized,"SUCCESS","角色："+role,requestId);return Map.of("user",publicUser(user),"temporaryPassword",password);});}
    public Map<String,Object> update(Identity actor,long id,String display,String role,String status,long expected,String requestId){require(actor,"admin-user:manage");display=displayName(display);validRole(role);if(!Set.of("ACTIVE","DISABLED").contains(status))throw bad("账号状态不正确");String shown=display;return store.atomic(()->{var u=existing(id);if(u.version()!=expected)throw new AdminFault(409,"STALE_USER","账号信息已变化，请刷新后再修改");if(u.role().equals("SUPER_ADMIN")&&u.status().equals("ACTIVE")&&(!role.equals("SUPER_ADMIN")||!status.equals("ACTIVE"))&&store.users().stream().filter(v->v.role().equals("SUPER_ADMIN")&&v.status().equals("ACTIVE")).count()==1)throw new AdminFault(409,"LAST_ADMIN","不能停用或降级最后一个启用的超级管理员");var changed=copy(u,shown,u.passwordHash(),role,status,u.mustChangePassword(),0,0,u.version()+1,clock.millis(),u.lastLoginAt());store.save(changed);if(!role.equals(u.role())||!status.equals(u.status()))store.revoke(id,null,clock.millis());audit(actor,"accounts","user-update",u.username(),"SUCCESS","角色："+u.role()+" → "+role+"；状态："+u.status()+" → "+status,requestId);return publicUser(changed);});}
    public Map<String,Object> resetPassword(Identity actor,long id,long expected,String requestId){require(actor,"admin-user:manage");if(id==actor.user().id())throw new AdminFault(403,"SELF_RESET_FORBIDDEN","请在个人菜单修改自己的密码");return store.atomic(()->{var u=existing(id);if(u.version()!=expected)throw new AdminFault(409,"STALE_USER","账号信息已变化，请刷新");String password=temporaryPassword();store.save(copy(u,u.displayName(),encode(password),u.role(),u.status(),true,0,0,u.version()+1,clock.millis(),u.lastLoginAt()));store.revoke(id,null,clock.millis());audit(actor,"accounts","password-reset",u.username(),"SUCCESS","重置密码，撤销全部会话",requestId);return Map.of("temporaryPassword",password,"username",u.username());});}
    public List<Map<String,Object>> sessions(Identity actor,long userId){if(userId!=actor.user().id())require(actor,"admin-session:manage");existing(userId);long now=clock.millis();return store.sessions(userId).stream().filter(s->s.revokedAt()==null&&s.expiresAt()>now).sorted(Comparator.comparingLong(Session::createdAt).reversed()).map(s->{Map<String,Object> out=new LinkedHashMap<>();out.put("id",s.id());out.put("createdAt",s.createdAt());out.put("expiresAt",s.expiresAt());out.put("lastSeenAt",s.lastSeenAt());out.put("device",s.device());out.put("current",s.id().equals(actor.session().id()));return out;}).toList();}
    public void revoke(Identity actor,long userId,String sessionId,String requestId){if(userId!=actor.user().id())require(actor,"admin-session:manage");store.atomic(()->{existing(userId);if(sessionId!=null&&store.sessions(userId).stream().noneMatch(s->s.id().equals(sessionId)))throw new AdminFault(404,"NOT_FOUND","会话不存在或不属于该用户");store.revoke(userId,sessionId,clock.millis());audit(actor,"security","session-revoke",Long.toString(userId),"SUCCESS",sessionId==null?"撤销该用户全部会话":"撤销会话 "+sessionId,requestId);return null;});}
    public Map<String,Object> auditPage(int page,int size,String actor,String action,String result,long from,long to){page(page,size);if(actor.length()>100||action.length()>64||!Set.of("all","SUCCESS","FAILED","DENIED").contains(result)||from<0||to<from)throw bad("审计筛选参数不正确");return store.auditPage(page,size,actor.strip(),action.strip(),result,from,to);}
    /** The metadata write and its successful audit share a database transaction. */
    public <T>T mutation(Identity actor,String permission,String module,String action,String object,Supplier<T> work,java.util.function.Function<T,String> target,String detail,String requestId){
        require(actor,permission);
        Supplier<T> audited=()->{T result=work.get();audit(actor,module,action,target==null?object:target.apply(result),"SUCCESS",detail,requestId);return result;};
        // Serialize rule publication across instances, including the initial missing active pointer.
        return module.equals("config")?store.atomic(audited):store.transaction(audited);
    }
    public void audit(Identity actor,String module,String action,String object,String result,String detail,String requestId){var u=actor==null?null:actor.user();store.audit(new Audit(UUID.randomUUID().toString(),u==null?null:u.id(),u==null?"":u.username(),u==null?"匿名":u.displayName(),safe(module,32),safe(action,64),safe(object,200),result,clock.millis(),safe(requestId,36),safe(detail,1000)));}
    /** Offline CLI only: no HTTP endpoint exposes recovery. */
    public void recover(String username,String password){String name=username(username);validatePassword(password);store.atomic(()->{var u=store.user(name).orElseThrow(()->new AdminFault(404,"NOT_FOUND","恢复账号不存在"));store.save(copy(u,u.displayName(),encode(password),"SUPER_ADMIN","ACTIVE",true,0,0,u.version()+1,clock.millis(),u.lastLoginAt()));store.revoke(u.id(),null,clock.millis());audit(null,"security","offline-recovery",name,"SUCCESS","运维恢复账号，要求首次改密",UUID.randomUUID().toString());return null;});}
    private User existing(long id){return store.user(id).orElseThrow(()->new AdminFault(404,"NOT_FOUND","后台账号不存在"));}
    private static User copy(User u,String name,String hash,String role,String status,boolean must,int failures,long locked,long version,long updated,long login){return new User(u.id(),u.username(),name,hash,role,status,must,failures,locked,version,u.createdAt(),updated,login);}
    public static String username(String s){if(s==null)throw bad("请输入账号");String n=s.strip().toLowerCase(Locale.ROOT);if(!n.matches("[a-z0-9][a-z0-9._-]{2,31}")||Set.of("admin","system").contains(n))throw bad("账号须为3～32位字母、数字、点、下划线或短横线，不能使用历史保留名admin/system");return n;}
    private static String displayName(String s){if(s==null||s.strip().isEmpty()||s.codePointCount(0,s.length())>32||s.codePoints().anyMatch(Character::isISOControl))throw bad("姓名须为1～32字且不能含控制符");return s.strip();}
    private static void validRole(String role){if(!ROLES.containsKey(role))throw bad("角色不正确");}
    public static void validatePassword(String p){if(p==null||p.codePointCount(0,p.length())<12||p.codePointCount(0,p.length())>64||p.getBytes(StandardCharsets.UTF_8).length>72||p.codePoints().anyMatch(Character::isISOControl))throw bad("密码须为12～64个字符，UTF-8不超过72字节且不能含控制符");}
    private String encode(String p){return "{bcrypt}"+passwords.encode(p);}
    private static String rawHash(String p){if(!p.startsWith("{bcrypt}"))throw new AdminFault(503,"ADMIN_UNAVAILABLE","账号凭证格式不可用，请联系管理员");return p.substring(8);}
    private String temporaryPassword(){byte[] bytes=new byte[18];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static String hash(String p){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String safe(String s,int max){return s==null?"":s.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(max,s.length()));}
    public static void page(int p,int s){if(p<1||p>1000000||s<1||s>100)throw bad("页码须为1～1000000，每页1～100条");}
    private static AdminFault bad(String message){return new AdminFault(400,"BAD_REQUEST",message);}
    public static AdminFault unauthorized(){return new AdminFault(401,"UNAUTHENTICATED","登录已失效，请重新登录");}
}
