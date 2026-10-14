package com.millionnaire.gateway.room;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.gateway.auth.UserStore.User;
import com.millionnaire.engine.core.command.RoomCommand.SetReady;
import com.millionnaire.engine.core.command.SessionCommand.StartGame;
import java.time.*;
import org.junit.jupiter.api.Test;
class AdminRoomSnapshotTest {
    @Test void summaryObservesWaitingAndPlayingWithoutExposingPrivateState() throws Exception {
        RoomStore store=mock(RoomStore.class);when(store.open(eq(101L),any())).thenReturn(new RoomStore.Opened(1001,123456));
        Clock clock=Clock.fixed(Instant.ofEpochMilli(10000),ZoneOffset.UTC);
        var service=new RoomService(store,(player,json)->{},new Wire(new ObjectMapper()),clock);
        try {
            var host=new User(101,"房主");var guest=new User(102,"朋友");
            var room=service.create(host,"create",null).room();service.join(guest,123456,"join");
            var waiting=room.adminSummary();assertThat(waiting.status()).isEqualTo("WAITING");assertThat(waiting.players()).isEqualTo(2);
            assertThat(waiting.code()).isEqualTo("123456");assertThat(waiting.createdAt()).isEqualTo(10000);
            room.submitClient(host.playerId(),"ready1",new SetReady(host.playerId(),true));room.submitClient(guest.playerId(),"ready2",new SetReady(guest.playerId(),true));
            var start=room.submitClient(host.playerId(),"start",new StartGame(host.playerId()));assertThat(start.ok()).isTrue();
            var active=service.adminRooms().getFirst();assertThat(active.status()).isEqualTo("PLAYING");assertThat(active.gameNo()).isPositive();
            var json=new ObjectMapper().writeValueAsString(active);
            assertThat(json).doesNotContain("房主","朋友","playerId","hand","seed","token","balance","ledger");
        } finally {service.shutdown();}
    }
}
