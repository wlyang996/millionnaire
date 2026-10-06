package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.serialize.Canonical;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SessionGoldenTest {
    @Test void scriptedSessionDebtToFinalLogHasStableBytes() {
        var t = M3aTest.realDebt();
        t.send(Math.max(t.now + 1, t.window().window().opensAt()), new GameCommand.DeclareBankruptcy(t.game().debt().debtor(), t.window().windowId()));
        assertFalse(t.session().inGame());
        assertEquals(t.state, t.engine.rebuild(t.log));
        String bytes = t.engine.encodeEvents(t.log);
        assertEquals(bytes, t.engine.encodeEvents(t.engine.decodeEvents(bytes)));
        assertEquals("46953a081aa15740d69b5fbee5670e036bff181e77ac3130cd3e40e68135e5de",
                Canonical.sha256Hex(bytes.replace("engine-0.9.0-m3b", "engine-0.8.1-m3a").getBytes(StandardCharsets.UTF_8)),
                "historical session golden changes only its version string");
        assertEquals("dc610456ff90ac47e93186b1d44e662440288099a89428a34933c1fecef59381", Canonical.sha256Hex(bytes.getBytes(StandardCharsets.UTF_8)));
    }
}
