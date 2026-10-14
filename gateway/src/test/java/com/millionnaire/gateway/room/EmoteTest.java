package com.millionnaire.gateway.room;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.gateway.auth.UserStore.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;
class EmoteTest {
    @Test void fixedEmotesReachMembersAndCannotBeSpoofedOrFlooded(){
        var sent=new ArrayList<String>();var clock=Clock.systemUTC();
        var store=new RoomStore(new StaticListableBeanFactory().getBeanProvider(JdbcTemplate.class),clock);
        var rooms=new RoomService(store,(id,json)->sent.add(id+":"+json),new Wire(new ObjectMapper()),clock);
        try{
            var r=rooms.create(new User(1,"可可"),"create",null).room();
            rooms.join(new User(2,"阿杰"),r.codeNumber(),"join");sent.clear();
            r.emote("1","first","CHEER");
            r.emote("1","first","CHEER"); // Reconnect retries acknowledge without rebroadcasting.
            assertThat(sent).hasSize(2).allSatisfy(s->{assertThat(s).contains("\"playerId\":\"1\"","CHEER","endsAt");});
            assertThatThrownBy(()->r.emote("1","WOW")).isInstanceOf(ClientException.class).hasMessageContaining("太快");
            assertThatThrownBy(()->r.emote("2","<script>")).isInstanceOf(ClientException.class).hasMessageContaining("未知");
            assertThatThrownBy(()->r.emote("3","CHEER")).isInstanceOf(ClientException.class);
        }finally{rooms.shutdown();}
    }
}
