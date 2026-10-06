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
        // Preserve the historical behavioral golden after removing only this revision's source metadata.
        var historicalEvents = t.log.stream().map(e -> {
            if (e instanceof com.millionnaire.engine.core.event.KernelEvent.InputAccepted a
                    && a.clientCommand() != null && !(a.clientCommand() instanceof GameCommand.DeclareBankruptcy)
                    && !(a.clientCommand() instanceof GameCommand.Surrender)) {
                return (com.millionnaire.engine.core.event.Event) new com.millionnaire.engine.core.event.KernelEvent.InputAccepted(
                        a.seq(), a.at(), a.digest(), a.systemCommand(), null);
            }
            return e;
        }).toList();
        String historicalBytes = t.engine.encodeEvents(historicalEvents)
                .replace("engine-0.9.1-m3b", "engine-0.8.1-m3a").replace(",\"controlSource\":null", "");
        assertEquals("46953a081aa15740d69b5fbee5670e036bff181e77ac3130cd3e40e68135e5de",
                Canonical.sha256Hex(historicalBytes.getBytes(StandardCharsets.UTF_8)),
                "historical session behavior remains identical after removing the new source metadata");
        assertEquals("a03bda62d0369e888fff87aab9f831253cd13b840dd4bc48a7f7fb37f0e3fcb3", Canonical.sha256Hex(bytes.getBytes(StandardCharsets.UTF_8)));
    }
}
