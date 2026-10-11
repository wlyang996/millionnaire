package com.millionnaire.gateway.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.millionnaire.engine.core.command.GameCommand;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TeamAidWireTest {
    @Test void aidCommandsAlwaysUseAuthenticatedActorRatherThanClientActor() {
        var json = new ObjectMapper(); var wire = new Wire(json);
        var args = json.createObjectNode().put("actor", "forged-teammate").put("gameNo", 1)
                .put("windowId", 9).put("recipient", "ally").put("amount", 300).put("debtId", 7);
        assertThat(wire.gameCommand("TeamAid", args, "authenticated"))
                .isEqualTo(new GameCommand.TeamAid("authenticated", 1, 9, "ally", 300, 7));
        args.remove("recipient"); args.remove("amount");
        assertThat(wire.gameCommand("RequestTeamAid", args, "authenticated"))
                .isEqualTo(new GameCommand.RequestTeamAid("authenticated", 1, 9, 7));
    }
}
