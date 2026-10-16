package com.millionnaire.gateway.admin;

import com.millionnaire.gateway.audio.*;
import com.millionnaire.gateway.characters.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MediaLibrary {
    private final MediaObjectRegistry registry;private final AudioCatalog audio;private final CharacterCatalog characters;
    private final AudioObjectStore sounds;private final CharacterObjectStore images;private final ReleaseWorkflow releases;private final AdminStore store;private final AdminAuth auth;private final Clock clock;
    public MediaLibrary(MediaObjectRegistry registry,AudioCatalog audio,CharacterCatalog characters,AudioObjectStore sounds,CharacterObjectStore images,ReleaseWorkflow releases,AdminStore store,AdminAuth auth,Clock clock){this.registry=registry;this.audio=audio;this.characters=characters;this.sounds=sounds;this.images=images;this.releases=releases;this.store=store;this.auth=auth;this.clock=clock;}
    private void discover(){for(var a:audio.assets())if(!a.builtin())registry.register(a.key(),"audio",a.info().bytes());for(var p:characters.packs())for(var a:p.character().images().values())registry.register(a.key(),"character",a.bytes());}
    private Map<String,List<String>> references(){
        var refs=new LinkedHashMap<>(audio.managementReferences());
        // Published packs are immutable and retained for every historical catalog and active room.
        for(var pack:characters.packs())for(var asset:pack.character().images().values())refs.computeIfAbsent(asset.key(),k->new ArrayList<>()).add((pack.published()?"已发布人物素材包 ":"人物草稿 ")+pack.character().name()+" / "+pack.packId());
        for(var r:releases.all())if(Set.of("SUBMITTED","APPROVED").contains(r.status())&&r.module().equals("audio")){
            var payload=r.payload().path("settings");for(var asset:audio.assets())if(payload.toString().contains("\""+asset.id()+"\""))refs.computeIfAbsent(asset.key(),k->new ArrayList<>()).add("发布审批 "+r.title());
        }
        return refs;
    }
    public Map<String,Object> page(int page,int size,String q,String status){
        if(page<1||page>100000||size<1||size>100||q.length()>100||!Set.of("all","ACTIVE","TRASHED","PURGING","PURGE_FAILED","DELETED","UNUSED").contains(status))throw new AdminFault(400,"BAD_REQUEST","素材筛选参数不正确");
        return store.atomic(()->{discover();var refs=references();var rows=registry.all().stream().filter(r->r.key().contains(q)&&(status.equals("all")||status.equals("UNUSED")&&r.status().equals("ACTIVE")&&!refs.containsKey(r.key())||r.status().equals(status))).toList();
            var items=rows.stream().skip((long)(page-1)*size).limit(size).map(r->{var out=new LinkedHashMap<String,Object>();out.put("key",r.key());out.put("kind",r.kind());out.put("bytes",r.bytes());out.put("createdAt",r.createdAt());out.put("trashedAt",r.trashedAt());out.put("status",r.status());out.put("version",r.version());out.put("references",refs.getOrDefault(r.key(),List.of()));out.put("purgeAfter",r.trashedAt()==null?null:r.trashedAt()+7L*86400000);return out;}).toList();return Map.of("items",items,"total",rows.size(),"persistent",store.persistent());});
    }
    public Object action(AdminAuth.Identity actor,String key,long version,String action,String confirmation,String requestId){AdminAuth.require(actor,"media:manage");if(key==null||action==null)throw new AdminFault(400,"BAD_REQUEST","缺少素材路径或操作");if(action.equals("purge"))return purge(actor,key,version,confirmation,requestId);return store.atomic(()->{
        var r=registry.all().stream().filter(v->v.key().equals(key)).findFirst().orElseThrow(()->new AdminFault(404,"NOT_FOUND","素材记录不存在"));
        if(r.version()!=version)throw new AdminFault(409,"STALE_MEDIA","素材状态已变化，请刷新");
        if(!key.startsWith("audio/")&&!key.startsWith("characters/"))throw new AdminFault(400,"BAD_REQUEST","素材路径不在管理范围");
        var refs=references().getOrDefault(key,List.of());String next;
        if(action.equals("restore")){if(!r.status().equals("TRASHED"))throw new AdminFault(409,"INVALID_STATE","该素材不在回收站");next="ACTIVE";}
        else {if(!refs.isEmpty())throw new AdminFault(409,"MEDIA_REFERENCED","素材仍被草稿、历史版本或审批单引用，不能清理");
            if(action.equals("trash")){if(!r.status().equals("ACTIVE"))throw new AdminFault(409,"INVALID_STATE","素材状态不允许移入回收站");next="TRASHED";}
            else throw new AdminFault(400,"BAD_REQUEST","未知素材操作");
        }
        var changed=registry.change(key,version,next);auth.audit(actor,"media","media-"+action,key,"SUCCESS",next,requestId);return changed;
    });}
    private Object purge(AdminAuth.Identity actor,String key,long version,String confirmation,String requestId){
        // Commit the irreversible intent before COS IO. A crash can never make a missing object look recoverable.
        var pending=store.atomic(()->{var r=registry.all().stream().filter(v->v.key().equals(key)).findFirst().orElseThrow(()->new AdminFault(404,"NOT_FOUND","素材记录不存在"));if(r.version()!=version)throw new AdminFault(409,"STALE_MEDIA","素材状态变化，请刷新");if(!Set.of("TRASHED","PURGING","PURGE_FAILED").contains(r.status())||r.trashedAt()==null||clock.millis()<r.trashedAt()+7L*86400000)throw new AdminFault(409,"RETENTION_REQUIRED","回收站至少保留7天后才可永久清理");if(!key.equals(confirmation))throw new AdminFault(400,"CONFIRMATION_REQUIRED","请核对并确认完整素材路径");if(!references().getOrDefault(key,List.of()).isEmpty())throw new AdminFault(409,"MEDIA_REFERENCED","素材仍被引用");var changed=registry.change(key,version,"PURGING");auth.audit(actor,"media","media-purge-start",key,"SUCCESS","开始永久清理；完成前不能恢复",requestId);return changed;});
        try{return store.atomic(()->{var current=registry.all().stream().filter(r->r.key().equals(key)).findFirst().orElseThrow();if(current.version()!=pending.version())throw new AdminFault(409,"STALE_MEDIA","已有其他清理任务，请刷新");if(!references().getOrDefault(key,List.of()).isEmpty())throw new AdminFault(409,"MEDIA_REFERENCED","素材引用已变化");if(pending.kind().equals("audio"))sounds.delete(key);else images.delete(key);var done=registry.change(key,pending.version(),"DELETED");auth.audit(actor,"media","media-purge",key,"SUCCESS","永久清理完成",requestId);return done;});}
        catch(RuntimeException failure){store.atomic(()->{var current=registry.all().stream().filter(r->r.key().equals(key)).findFirst().orElseThrow();if(current.version()==pending.version()){registry.change(key,current.version(),"PURGE_FAILED");auth.audit(actor,"media","media-purge",key,"FAILED","永久清理未完成，可核对后重试；不能恢复",requestId);}return null;});if(failure instanceof AdminFault fault)throw fault;throw new AdminFault(502,"MEDIA_PURGE_FAILED","素材清理未完成，请核对存储状态后重试");}
    }
}
