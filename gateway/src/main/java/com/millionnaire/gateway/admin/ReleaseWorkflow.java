package com.millionnaire.gateway.admin;

import com.fasterxml.jackson.databind.*;
import com.millionnaire.gateway.config.*;
import com.millionnaire.gateway.audio.AudioCatalog;
import com.millionnaire.gateway.characters.CharacterCatalog;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;

/** Immutable submissions; publication and completion share the accounts guard and DB transaction. */
@Service @EnableScheduling
public class ReleaseWorkflow {
    public record Request(String id,String module,String title,JsonNode payload,long submittedBy,Long approvedBy,String status,long scheduledAt,long createdAt,long updatedAt,long version,String result) {}
    private final ObjectProvider<JdbcTemplate> jdbc; private final AdminStore store; private final AdminAuth auth;
    private final GameConfigs configs; private final AudioCatalog audio; private final CharacterCatalog characters; private final Clock clock;
    private final ObjectMapper json=new ObjectMapper(); private final Map<String,Request> memory=new LinkedHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired private com.millionnaire.gateway.characters.CharacterObjectStore images;
    public ReleaseWorkflow(ObjectProvider<JdbcTemplate> jdbc,AdminStore store,AdminAuth auth,GameConfigs configs,AudioCatalog audio,CharacterCatalog characters,Clock clock){this.jdbc=jdbc;this.store=store;this.auth=auth;this.configs=configs;this.audio=audio;this.characters=characters;this.clock=clock;}
    public List<Request> all(){var db=jdbc.getIfAvailable();return db==null?List.copyOf(memory.values()):db.query("SELECT * FROM admin_release_request ORDER BY created_at DESC",(r,i)->new Request(r.getString("request_id"),r.getString("module"),r.getString("title"),read(r.getString("payload")),r.getLong("submitted_by"),r.getObject("approved_by",Long.class),r.getString("status"),r.getLong("scheduled_at"),r.getLong("created_at"),r.getLong("updated_at"),r.getLong("version"),r.getString("result")));}
    private JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("审批记录格式错误",e);}}
    private Request one(String id){return all().stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->fault(404,"NOT_FOUND","审批单不存在"));}
    private void save(Request r,boolean insert){var db=jdbc.getIfAvailable();if(db==null){memory.put(r.id(),r);return;}if(insert)db.update("INSERT INTO admin_release_request VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",r.id(),r.module(),r.title(),r.payload().toString(),r.submittedBy(),r.approvedBy(),r.status(),r.scheduledAt(),r.createdAt(),r.updatedAt(),r.version(),r.result());else db.update("UPDATE admin_release_request SET approved_by=?,status=?,updated_at=?,version=?,result=? WHERE request_id=?",r.approvedBy(),r.status(),r.updatedAt(),r.version(),r.result(),r.id());}
    private String permission(String module){if(module==null||!Set.of("config","audio","character").contains(module))throw fault(400,"BAD_REQUEST","未知发布模块");return module+":publish";}
    public boolean visible(AdminAuth.Identity actor,Request r){return actor.grants().contains(permission(r.module()))&&actor.grants().contains(r.module().equals("config")?"config:read":r.module()+":draft");}
    public Map<String,Object> page(AdminAuth.Identity actor,int page,int size,String status){if(page<1||page>100000||size<1||size>100||!Set.of("all","SUBMITTED","APPROVED","PUBLISHED","FAILED","REJECTED","CANCELLED").contains(status))throw fault(400,"BAD_REQUEST","审批筛选参数不正确");return store.atomic(()->{var rows=all().stream().filter(r->visible(actor,r)&&(status.equals("all")||r.status().equals(status))).sorted(Comparator.comparingLong(Request::createdAt).reversed()).toList();return Map.of("items",rows.stream().skip((long)(page-1)*size).limit(size).map(r->Map.of("id",r.id(),"module",r.module(),"title",r.title(),"status",r.status(),"scheduledAt",r.scheduledAt(),"submittedBy",r.submittedBy(),"createdAt",r.createdAt(),"version",r.version(),"result",r.result())).toList(),"total",rows.size(),"persistent",store.persistent());});}
    public Request detail(AdminAuth.Identity actor,String id){return store.atomic(()->{var r=one(id);if(!visible(actor,r))throw fault(403,"FORBIDDEN","没有此模块的发布权限");return r;});}
    public byte[] preview(Request request,String asset){
        if(request.module().equals("audio")){if(!request.payload().path("settings").toString().contains("\""+asset+"\""))throw fault(404,"NOT_FOUND","审批单未引用该素材");return audio.bytes(asset,true);}
        if(request.module().equals("character")){var image=request.payload().path("pack").path("character").path("images").get(asset);if(image==null||!com.millionnaire.gateway.characters.CharacterImages.ALL_SLOTS.contains(asset))throw fault(404,"NOT_FOUND","审批单未引用该素材");var bytes=images.get(image.path("key").asText());if(!com.millionnaire.gateway.characters.CharacterImages.inspect(asset,bytes).sha256().equals(image.path("sha256").asText()))throw fault(409,"MEDIA_CHANGED","素材完整性校验失败");return bytes;}
        throw fault(404,"NOT_FOUND","该审批单没有素材预览");
    }
    public Request submit(AdminAuth.Identity actor,String module,String title,JsonNode payload,long scheduledAt,String requestId){
        AdminAuth.require(actor,"release:submit");AdminAuth.require(actor,permission(module));
        if(title==null||title.isBlank()||title.length()>100||payload==null||payload.toString().length()>2_000_000||scheduledAt<0||scheduledAt>clock.millis()+90L*86400000)throw fault(400,"BAD_REQUEST","填写标题并选择未来90天内的发布时间");
        return store.atomic(()->{JsonNode frozen=capture(module,payload);long now=clock.millis();var r=new Request(UUID.randomUUID().toString(),module,title.strip(),frozen,actor.user().id(),null,"SUBMITTED",scheduledAt,now,now,1,"");if(!visible(actor,r))throw fault(403,"FORBIDDEN","没有草稿读取权限");save(r,true);auth.audit(actor,"releases","release-submit",r.id(),"SUCCESS","提交"+module+"发布审批",requestId);return r;});
    }
    private JsonNode capture(String module,JsonNode p){try{
        if(module.equals("config")){var settings=json.treeToValue(p.get("settings"),GameSettings.class);var errors=SettingsMapper.validate(settings);if(!errors.isEmpty())throw fault(400,"INVALID_CONFIG",String.join("；",errors));if(!p.hasNonNull("expectedActiveId")||p.path("expectedActiveId").asLong()!=configs.activeId())throw fault(409,"STALE_CONFIG","规则版本变化，请刷新后提交");}
        if(module.equals("audio")){var draft=audio.draft();if(!p.hasNonNull("revision")||!p.hasNonNull("expectedVersion")||p.path("revision").asInt()!=draft.revision()||p.path("expectedVersion").asLong()!=draft.baseVersion())throw fault(409,"STALE_AUDIO","音频草稿变化，请保存并刷新后提交");var frozen=p.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)frozen).set("settings",json.valueToTree(draft.settings()));return frozen;}
        if(module.equals("character")){var pack=characters.pack(p.path("packId").asText());if(pack.published()||!p.hasNonNull("revision")||p.path("revision").asInt()!=pack.revision()||!p.hasNonNull("expectedVersion")||p.path("expectedVersion").asLong()!=characters.current().version())throw fault(409,"STALE_CHARACTER","人物草稿或目录版本变化");var frozen=p.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)frozen).set("pack",json.valueToTree(pack));return frozen;}
        return p.deepCopy();
    }catch(AdminFault e){throw e;}catch(Exception e){throw fault(400,"BAD_REQUEST","发布内容格式不正确");}}
    public Request decide(AdminAuth.Identity actor,String id,long version,String action,String note,String requestId){return store.atomic(()->{
        if(action==null)throw fault(400,"BAD_REQUEST","请选择审批操作");
        var r=one(id);if(!visible(actor,r))throw fault(403,"FORBIDDEN","没有此模块权限");if(r.version()!=version)throw fault(409,"STALE_RELEASE","审批状态已变化，请刷新");
        String next;
        if(action.equals("cancel")){if(actor.user().id()!=r.submittedBy()&&!actor.grants().contains("release:approve"))throw fault(403,"FORBIDDEN","仅提交人或审核人可撤销");if(!Set.of("SUBMITTED","APPROVED").contains(r.status()))throw fault(409,"INVALID_STATE","该审批单已结束");next="CANCELLED";}
        else {AdminAuth.require(actor,"release:approve");if(actor.user().id()==r.submittedBy())throw fault(403,"SELF_APPROVAL","提交人不能审核自己的发布");if(!r.status().equals("SUBMITTED"))throw fault(409,"INVALID_STATE","该审批单不在待审核状态");if(!Set.of("approve","reject").contains(action))throw fault(400,"BAD_REQUEST","未知审批操作");next=action.equals("approve")?"APPROVED":"REJECTED";}
        if(note==null||note.length()>500||(next.equals("REJECTED")&&note.isBlank()))throw fault(400,"BAD_REQUEST","驳回请填写原因，最多500字");
        var changed=new Request(r.id(),r.module(),r.title(),r.payload(),r.submittedBy(),next.equals("APPROVED")?Long.valueOf(actor.user().id()):r.approvedBy(),next,r.scheduledAt(),r.createdAt(),clock.millis(),r.version()+1,note);save(changed,false);auth.audit(actor,"releases","release-"+action,id,"SUCCESS",note,requestId);return changed;
    });}
    @Scheduled(fixedDelay=10000,initialDelay=10000) public void tick(){for(var r:store.atomic(this::all))if(r.status().equals("APPROVED")&&r.scheduledAt()<=clock.millis())execute(r.id());}
    public void execute(String id){try{store.atomic(()->{var r=one(id);if(!r.status().equals("APPROVED")||r.scheduledAt()>clock.millis())return null;
        var submitter=auth.currentIdentity(r.submittedBy());var approver=auth.currentIdentity(Objects.requireNonNull(r.approvedBy()));AdminAuth.require(submitter,permission(r.module()));AdminAuth.require(approver,"release:approve");if(!visible(submitter,r)||!visible(approver,r))throw fault(403,"FORBIDDEN","发布权限已变化");
        var p=r.payload();String published;
        if(r.module().equals("config"))published=Long.toString(configs.publish(json.convertValue(p.get("settings"),GameSettings.class),p.path("note").asText(r.title()),submitter.user().username(),p.path("expectedActiveId").asLong(),null).configId());
        else if(r.module().equals("audio")){if(!json.valueToTree(audio.draft().settings()).equals(p.get("settings")))throw fault(409,"STALE_AUDIO","音频草稿内容已变化");published=Long.toString(audio.publish(p.path("revision").asInt(),p.path("expectedVersion").asLong()).version());}
        else {if(!json.valueToTree(characters.pack(p.path("packId").asText())).equals(p.get("pack")))throw fault(409,"STALE_CHARACTER","人物草稿内容已变化");published=Long.toString(characters.publish(p.path("packId").asText(),p.path("revision").asInt(),p.path("expectedVersion").asLong()).version());}
        save(new Request(r.id(),r.module(),r.title(),r.payload(),r.submittedBy(),r.approvedBy(),"PUBLISHED",r.scheduledAt(),r.createdAt(),clock.millis(),r.version()+1,"已发布版本 #"+published),false);auth.audit(submitter,r.module(),"publish",published,"SUCCESS","审批单 "+id+"；审核人 "+approver.user().username(),id);return null;
    });}catch(Exception e){store.atomic(()->{var r=one(id);if(r.status().equals("APPROVED")){String message=e instanceof AdminFault||e instanceof com.millionnaire.gateway.room.ClientException?e.getMessage():"发布执行失败，请联系管理员检查服务日志";save(new Request(r.id(),r.module(),r.title(),r.payload(),r.submittedBy(),r.approvedBy(),"FAILED",r.scheduledAt(),r.createdAt(),clock.millis(),r.version()+1,message.substring(0,Math.min(1000,message.length()))),false);auth.audit(null,"releases","release-execute",id,"FAILED",message,id);}return null;});}}
    private static AdminFault fault(int status,String code,String message){return new AdminFault(status,code,message);}
}
