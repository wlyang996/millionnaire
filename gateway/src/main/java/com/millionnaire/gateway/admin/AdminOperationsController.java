package com.millionnaire.gateway.admin;

import com.millionnaire.gateway.auth.UserStore;
import com.millionnaire.gateway.record.*;
import com.millionnaire.gateway.room.Wire;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/admin-api/v1")
public class AdminOperationsController {
    private final UserStore users; private final GameRecords records; private final AdminAuth auth; private final Wire wire;
    public AdminOperationsController(UserStore users,GameRecords records,AdminAuth auth,Wire wire){this.users=users;this.records=records;this.auth=auth;this.wire=wire;}
    @AdminAccess("player:read") @GetMapping("/players")
    public Object players(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="")String q){return users.adminPage(page,size,q.strip());}
    @AdminAccess("player:read") @GetMapping("/players/{id}")
    public Object player(@PathVariable long id,HttpServletRequest request){
        var user=users.adminProfile(id);
        auth.audit(AdminWeb.actor(request),"players","player-view",Long.toString(id),"SUCCESS","查询玩家资料与统计",AdminWeb.requestId(request));
        return Map.of("user",user,"stats",records.stats(id));
    }
    @AdminAccess("game:read") @GetMapping("/games")
    public Object games(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="")String room,@RequestParam(required=false)Long player,@RequestParam(defaultValue="0")long from,@RequestParam(defaultValue="9223372036854775807")long to){return records.adminPage(page,size,room.strip(),player,from,to);}
    @AdminAccess("game:read") @GetMapping("/games/{room}/{game}")
    public Object game(@PathVariable long room,@PathVariable long game,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="50")int size,HttpServletRequest request){
        if(page<1||page>100000||size<1||size>100)throw new AdminFault(400,"BAD_REQUEST","事件分页参数不正确");
        var detail=records.adminDetail(room,game).orElseThrow(()->new AdminFault(404,"NOT_FOUND","对局不存在或明细已清理"));
        List<com.millionnaire.engine.core.event.Event> events=detail.events()==null?List.of():EventLogs.decode(detail.events());
        var out=new LinkedHashMap<String,Object>();out.put("header",detail.header());out.put("initialCash",detail.initialCash());
        out.put("seats",detail.seats().stream().map(s->{var p=new LinkedHashMap<String,Object>();p.put("userId",s.userId());p.put("nickname",users.find(s.userId()).map(UserStore.User::nickname).orElse("已注销玩家"));p.put("rank",s.rank());p.put("netWorth",s.netWorth());p.put("cash",s.cash());p.put("life",s.life());return p;}).toList());
        out.put("events",wire.events(events.stream().skip((long)(page-1)*size).limit(size).toList()));out.put("totalEvents",events.size());out.put("logAvailable",detail.events()!=null);
        auth.audit(AdminWeb.actor(request),"games","game-view",room+"/"+game,"SUCCESS","查询结算和公开事件；第"+page+"页",AdminWeb.requestId(request));return out;
    }
}
