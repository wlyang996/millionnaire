package com.millionnaire.gateway.room;

import com.millionnaire.gateway.admin.AdminAccess;
import java.time.Clock;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class AdminRoomsController {
    private final RoomService rooms;private final Clock clock;
    public AdminRoomsController(RoomService rooms,Clock clock){this.rooms=rooms;this.clock=clock;}
    @AdminAccess("dashboard:read") @GetMapping("/admin-api/v1/rooms")
    public Map<String,Object> list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,
                                   @RequestParam(defaultValue="")String q,@RequestParam(defaultValue="all")String status) {
        if(page<1 || size<1 || size>100 || q.length()>30 || !java.util.Set.of("all","WAITING","PLAYING").contains(status))
            throw new ClientException("BAD_REQUEST","房间查询参数不正确");
        var all=rooms.adminRooms();var filtered=all.stream().filter(room->room.code().contains(q.strip()) && (status.equals("all") || status.equals(room.status()))).toList();
        long offset=(long)(page-1)*size;
        var selected=offset>=filtered.size()?java.util.List.of():filtered.subList((int)offset,Math.min(filtered.size(),(int)offset+size));
        return Map.of("items",selected,"total",filtered.size(),"page",page,"size",size,"sampledAt",clock.millis(),"scope","current-instance",
            "totals",Map.of("rooms",all.size(),"playing",all.stream().filter(r->r.status().equals("PLAYING")).count(),
                "players",all.stream().mapToInt(AdminRoomSnapshot::players).sum(),"automated",all.stream().mapToInt(AdminRoomSnapshot::automated).sum(),
                "spectators",all.stream().mapToInt(AdminRoomSnapshot::spectators).sum()));
    }
    @ExceptionHandler(ClientException.class) public org.springframework.http.ResponseEntity<?> bad(ClientException error) {
        return org.springframework.http.ResponseEntity.badRequest().body(Map.of("code",error.code(),"message",error.getMessage()));
    }
}
