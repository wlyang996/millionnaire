package com.millionnaire.gateway.admin;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** DB is authoritative whenever configured; a failed DB never falls back to memory. */
@Component
public class AdminStore {
    public record User(long id,String username,String displayName,String passwordHash,String role,String status,
                       boolean mustChangePassword,int failures,long lockedUntil,long version,long createdAt,long updatedAt,long lastLoginAt) {}
    public record Session(String id,String tokenHash,long userId,long createdAt,long expiresAt,Long revokedAt,long lastSeenAt,String device) {}
    public record Audit(String eventId,Long actorUserId,String actorUsername,String actorName,String module,String action,
                        String objectId,String result,long createdAt,String requestId,String detail) {}
    public record Rate(long windowStarted,int attempts) {}
    private final Supplier<JdbcTemplate> jdbc;
    private final Map<Long,User> users=new HashMap<>();
    private final Map<String,Session> sessions=new HashMap<>();
    private final List<Audit> audits=new ArrayList<>();
    private final Map<String,Rate> rates=new HashMap<>();
    public record Role(String id,String name,String description,Set<String> permissions,long version) {}
    private final Map<String,Role> roles=new HashMap<>();
    public synchronized List<Role> roles(){var db=jdbc.get();return db==null?List.copyOf(roles.values()):db.query("SELECT * FROM admin_role ORDER BY role_id",(r,i)->new Role(r.getString("role_id"),r.getString("name"),r.getString("description"),r.getString("permissions").isEmpty()?Set.of():Set.copyOf(Arrays.asList(r.getString("permissions").split("\\n"))),r.getLong("version")));}
    public synchronized void saveRole(Role role,Long expected){
        var old=roles().stream().filter(r->r.id().equals(role.id())).findFirst().orElse(null);
        if(old==null?expected!=null:expected==null||old.version()!=expected)throw new AdminFault(409,"STALE_ROLE","角色已变化，请刷新后修改");
        var db=jdbc.get();if(db==null){roles.put(role.id(),role);return;}
        String permissions=String.join("\n",new TreeSet<>(role.permissions()));
        if(old==null)db.update("INSERT INTO admin_role VALUES(?,?,?,?,?)",role.id(),role.name(),role.description(),permissions,role.version());
        else db.update("UPDATE admin_role SET name=?,description=?,permissions=?,version=? WHERE role_id=?",role.name(),role.description(),permissions,role.version(),role.id());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AdminStore(ObjectProvider<JdbcTemplate> jdbc){this.jdbc=jdbc::getIfAvailable;}
    public AdminStore(Supplier<JdbcTemplate> jdbc){this.jdbc=jdbc;}
    public boolean persistent(){return jdbc.get()!=null;}
    public synchronized <T>T atomic(Supplier<T> work){return transaction(()->{var db=jdbc.get();if(db!=null)db.queryForObject("SELECT slot FROM admin_guard WHERE slot='accounts' FOR UPDATE",String.class);return work.get();});}
    public synchronized <T>T transaction(Supplier<T> work){var db=jdbc.get();if(db==null)return work.get();return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource()))).execute(s->work.get());}
    public synchronized List<User> users(){var db=jdbc.get();return db==null?new ArrayList<>(users.values()):db.query("SELECT * FROM admin_user",(r,i)->new User(r.getLong("user_id"),r.getString("username"),r.getString("display_name"),r.getString("password_hash"),r.getString("role"),r.getString("status"),r.getInt("must_change_password")!=0,r.getInt("failures"),r.getLong("locked_until"),r.getLong("version"),r.getLong("created_at"),r.getLong("updated_at"),r.getLong("last_login_at")));}
    public synchronized Optional<User> user(long id){var db=jdbc.get();return db==null?Optional.ofNullable(users.get(id)):db.query("SELECT * FROM admin_user WHERE user_id=?",(r,i)->readUser(r),id).stream().findFirst();}
    public synchronized Optional<User> user(String name){var db=jdbc.get();return db==null?users.values().stream().filter(u->u.username().equals(name)).findFirst():db.query("SELECT * FROM admin_user WHERE username=?",(r,i)->readUser(r),name).stream().findFirst();}
    private static User readUser(java.sql.ResultSet r)throws java.sql.SQLException{return new User(r.getLong("user_id"),r.getString("username"),r.getString("display_name"),r.getString("password_hash"),r.getString("role"),r.getString("status"),r.getInt("must_change_password")!=0,r.getInt("failures"),r.getLong("locked_until"),r.getLong("version"),r.getLong("created_at"),r.getLong("updated_at"),r.getLong("last_login_at"));}
    public synchronized void insert(User u){var db=jdbc.get();if(db==null){users.put(u.id(),u);return;}db.update("INSERT INTO admin_user VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",u.id(),u.username(),u.displayName(),u.passwordHash(),u.role(),u.status(),u.mustChangePassword()?1:0,u.failures(),u.lockedUntil(),u.version(),u.createdAt(),u.updatedAt(),u.lastLoginAt());}
    public synchronized void save(User u){var db=jdbc.get();if(db==null){users.put(u.id(),u);return;}db.update("UPDATE admin_user SET display_name=?,password_hash=?,role=?,status=?,must_change_password=?,failures=?,locked_until=?,version=?,updated_at=?,last_login_at=? WHERE user_id=?",u.displayName(),u.passwordHash(),u.role(),u.status(),u.mustChangePassword()?1:0,u.failures(),u.lockedUntil(),u.version(),u.updatedAt(),u.lastLoginAt(),u.id());}
    public synchronized void issue(Session s){var db=jdbc.get();if(db==null){sessions.put(s.id(),s);return;}db.update("INSERT INTO admin_session VALUES(?,?,?,?,?,?,?,?)",s.id(),s.tokenHash(),s.userId(),s.createdAt(),s.expiresAt(),s.revokedAt(),s.lastSeenAt(),s.device());}
    public synchronized List<Session> sessions(long userId){var db=jdbc.get();return db==null?sessions.values().stream().filter(s->s.userId()==userId).toList():db.query("SELECT * FROM admin_session WHERE user_id=? ORDER BY created_at DESC",(r,i)->new Session(r.getString("session_id"),r.getString("token_hash"),r.getLong("user_id"),r.getLong("created_at"),r.getLong("expires_at"),r.getObject("revoked_at",Long.class),r.getLong("last_seen_at"),r.getString("device")),userId);}
    public synchronized Optional<Session> token(String hash){var db=jdbc.get();if(db==null)return sessions.values().stream().filter(s->s.tokenHash().equals(hash)).findFirst();return db.query("SELECT * FROM admin_session WHERE token_hash=?",(r,i)->new Session(r.getString("session_id"),r.getString("token_hash"),r.getLong("user_id"),r.getLong("created_at"),r.getLong("expires_at"),r.getObject("revoked_at",Long.class),r.getLong("last_seen_at"),r.getString("device")),hash).stream().findFirst();}
    public synchronized void revoke(long userId,String id,long now){var db=jdbc.get();if(db==null){for(var s:List.copyOf(sessions.values()))if(s.userId()==userId&&(id==null||id.equals(s.id()))&&s.revokedAt()==null)sessions.put(s.id(),new Session(s.id(),s.tokenHash(),s.userId(),s.createdAt(),s.expiresAt(),now,s.lastSeenAt(),s.device()));}else if(id==null)db.update("UPDATE admin_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",now,userId);else db.update("UPDATE admin_session SET revoked_at=? WHERE user_id=? AND session_id=? AND revoked_at IS NULL",now,userId,id);}
    public synchronized void touch(Session s,long now){
        if(now-s.lastSeenAt()<60000)return;
        var db=jdbc.get();
        if(db==null){
            // A stale authentication read must never resurrect a concurrently revoked session.
            var current=sessions.get(s.id());
            if(current!=null&&current.revokedAt()==null)sessions.put(current.id(),new Session(current.id(),current.tokenHash(),current.userId(),current.createdAt(),current.expiresAt(),null,now,current.device()));
        }else db.update("UPDATE admin_session SET last_seen_at=? WHERE session_id=? AND last_seen_at<? AND revoked_at IS NULL",now,s.id(),now-60000);
    }
    public synchronized Rate rate(String key){var db=jdbc.get();if(db==null)return rates.get(key);return db.query("SELECT window_started,attempts FROM admin_login_limit WHERE source_hash=?",(r,i)->new Rate(r.getLong(1),r.getInt(2)),key).stream().findFirst().orElse(null);}
    public synchronized void rate(String key,Rate rate){var db=jdbc.get();if(db==null){rates.put(key,rate);return;}if(db.update("UPDATE admin_login_limit SET window_started=?,attempts=? WHERE source_hash=?",rate.windowStarted(),rate.attempts(),key)==0)db.update("INSERT INTO admin_login_limit VALUES(?,?,?)",key,rate.windowStarted(),rate.attempts());}
    public synchronized void audit(Audit a){var db=jdbc.get();if(db==null){audits.add(a);return;}db.update("INSERT INTO admin_audit VALUES(?,?,?,?,?,?,?,?,?,?,?)",a.eventId(),a.actorUserId(),a.actorUsername(),a.actorName(),a.module(),a.action(),a.objectId(),a.result(),a.createdAt(),a.requestId(),a.detail());}
    public synchronized Map<String,Object> auditPage(int page,int size,String actor,String action,String result,long from,long to){
        var db=jdbc.get();List<Audit> items;long total;int offset=(page-1)*size;
        if(db==null){var filtered=audits.stream().filter(a->(actor.isEmpty()||a.actorUsername().toLowerCase(Locale.ROOT).contains(actor.toLowerCase(Locale.ROOT)))&&(action.isEmpty()||a.action().contains(action))&&(result.equals("all")||a.result().equals(result))&&a.createdAt()>=from&&a.createdAt()<=to).sorted(Comparator.comparingLong(Audit::createdAt).thenComparing(Audit::eventId).reversed()).toList();total=filtered.size();items=filtered.stream().skip(offset).limit(size).toList();}
        else {String sql=" FROM admin_audit WHERE LOWER(actor_username) LIKE ? ESCAPE '!' AND action LIKE ? ESCAPE '!' AND created_at>=? AND created_at<=?"+(result.equals("all")?"":" AND result=?");var args=new ArrayList<Object>(List.of(like(actor.toLowerCase(Locale.ROOT)),like(action),from,to));if(!result.equals("all"))args.add(result);total=db.queryForObject("SELECT COUNT(*)"+sql,Long.class,args.toArray());args.add(size);args.add(offset);items=db.query("SELECT *"+sql+" ORDER BY created_at DESC,event_id DESC LIMIT ? OFFSET ?",(r,i)->new Audit(r.getString("event_id"),r.getObject("actor_user_id",Long.class),r.getString("actor_username"),r.getString("actor_name"),r.getString("module"),r.getString("action"),r.getString("object_id"),r.getString("result"),r.getLong("created_at"),r.getString("request_id"),r.getString("detail")),args.toArray());}
        return Map.of("items",items,"total",total,"page",page,"size",size);
    }
    private static String like(String s){return "%"+s.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    public synchronized Map<String,Object> publishers(String module){
        var db=jdbc.get();var out=new LinkedHashMap<String,Object>();
        if(db==null){audits.stream().filter(a->a.module().equals(module)&&a.result().equals("SUCCESS")&&Set.of("publish","rollback","configure").contains(a.action())).sorted(Comparator.comparingLong(Audit::createdAt).reversed()).forEach(a->out.putIfAbsent(a.objectId(),Map.of("username",a.actorUsername(),"displayName",a.actorName())));}
        else db.query("SELECT object_id,actor_username,actor_name FROM admin_audit WHERE module=? AND result='SUCCESS' AND action IN ('publish','rollback','configure') ORDER BY created_at DESC,event_id DESC LIMIT 100",(org.springframework.jdbc.core.RowCallbackHandler)r->out.putIfAbsent(r.getString(1),Map.of("username",r.getString(2),"displayName",r.getString(3))),module);
        return out;
    }
}
