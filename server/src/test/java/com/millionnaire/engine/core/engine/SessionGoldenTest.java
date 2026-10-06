package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.serialize.Canonical;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SessionGoldenTest {
    private static final String CONFIG_HASH = "15be5a0b1185660540974ce2b74ac25d73bfb205f06e368cdb3c79b0ed26b668";
    private static final String PREVIOUS_CONFIG_HASH = "f8524a62880278ca3e3a0e6838140e916f28cf1b2498111c53a29ddb38ef709d";

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
        // 配置新增正式服策略开关后（测试配置沿用旧规则），配置哈希从 f8524a62… 变为 15be5a0b…；其余字节必须不变
        String historicalBytes = t.engine.encodeEvents(historicalEvents)
                .replace("engine-0.9.1-m3b", "engine-0.8.1-m3a").replace(",\"controlSource\":null", "")
                .replace(CONFIG_HASH, PREVIOUS_CONFIG_HASH);
        assertEquals("46953a081aa15740d69b5fbee5670e036bff181e77ac3130cd3e40e68135e5de",
                Canonical.sha256Hex(historicalBytes.getBytes(StandardCharsets.UTF_8)),
                "historical session behavior remains identical after removing the new source metadata");
        assertEquals(CONFIG_HASH, t.config.contentHash());
        assertEquals("a03bda62d0369e888fff87aab9f831253cd13b840dd4bc48a7f7fb37f0e3fcb3",
                Canonical.sha256Hex(bytes.replace(CONFIG_HASH, PREVIOUS_CONFIG_HASH).getBytes(StandardCharsets.UTF_8)));
    }
}
