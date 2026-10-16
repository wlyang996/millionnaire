package com.millionnaire.gateway.admin;

import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MediaObjectRegistry {
    public record ObjectRow(String key,String kind,long bytes,long createdAt,Long trashedAt,String status,long version){}
    private final ObjectProvider<JdbcTemplate> jdbc;private final Clock clock;private final Map<String,ObjectRow> memory=new LinkedHashMap<>();
    public MediaObjectRegistry(ObjectProvider<JdbcTemplate> jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    public synchronized List<ObjectRow> all(){var db=jdbc.getIfAvailable();return db==null?List.copyOf(memory.values()):db.query("SELECT * FROM admin_media_object ORDER BY created_at DESC",(r,i)->new ObjectRow(r.getString("object_key"),r.getString("kind"),r.getLong("bytes_count"),r.getLong("created_at"),r.getObject("trashed_at",Long.class),r.getString("status"),r.getLong("version")));}
    public synchronized boolean available(String key){var db=jdbc.getIfAvailable();if(db==null){var r=memory.get(key);return r==null||r.status().equals("ACTIVE");}var status=db.queryForList("SELECT status FROM admin_media_object WHERE object_key=?",String.class,key);return status.isEmpty()||status.getFirst().equals("ACTIVE");}
    /** Independent registration retains evidence even if a later metadata CAS rolls back. */
    public synchronized void register(String key,String kind,long bytes){
        var db=jdbc.getIfAvailable();if(db==null){memory.putIfAbsent(key,new ObjectRow(key,kind,bytes,clock.millis(),null,"ACTIVE",1));return;}
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(s->{if(db.queryForObject("SELECT COUNT(*) FROM admin_media_object WHERE object_key=?",Long.class,key)==0)try{db.update("INSERT INTO admin_media_object VALUES(?,?,?, ?,?,NULL,NULL,'ACTIVE',1)",key,kind,"{}",bytes,clock.millis());}catch(org.springframework.dao.DuplicateKeyException ignored){}});
    }
    public synchronized ObjectRow change(String key,long expected,String next){var r=all().stream().filter(v->v.key().equals(key)).findFirst().orElseThrow(()->new AdminFault(404,"NOT_FOUND","素材记录不存在"));if(r.version()!=expected)throw new AdminFault(409,"STALE_MEDIA","素材状态变化，请刷新");var n=new ObjectRow(r.key(),r.kind(),r.bytes(),r.createdAt(),next.equals("TRASHED")?clock.millis():r.trashedAt(),next,r.version()+1);var db=jdbc.getIfAvailable();if(db==null)memory.put(key,n);else db.update("UPDATE admin_media_object SET trashed_at=?,status=?,version=? WHERE object_key=?",n.trashedAt(),n.status(),n.version(),key);return n;}
}
