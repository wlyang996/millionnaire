package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.serialize.Canonical;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SessionGoldenTest {
    private static final String CONFIG_HASH = "15be5a0b1185660540974ce2b74ac25d73bfb205f06e368cdb3c79b0ed26b668";
    /** 测试配置新增道具开关（cardsEnabled，测试配置关闭）后的配置哈希；换回 CONFIG_HASH 后历史黄金值不变。 */
    private static final String CARDS_CONFIG_HASH = "2af045b6dcd66a72257f8b02fc2f62acb79cfb406fb89ddd2e0fda288cc06672"; // 租金上涨参数进入配置后（之前 993156de…，事件拆分）
    private static final String PREVIOUS_CONFIG_HASH = "f8524a62880278ca3e3a0e6838140e916f28cf1b2498111c53a29ddb38ef709d";

    @Test void scriptedSessionDebtToFinalLogHasStableBytes() {
        var t = M3aTest.realDebt();
        t.send(Math.max(t.now + 1, t.window().window().opensAt()), new GameCommand.DeclareBankruptcy(t.game().debt().debtor(), t.window().windowId()));
        assertFalse(t.session().inGame());
        assertEquals(t.state, t.engine.rebuild(t.log));
        String raw = t.engine.encodeEvents(t.log);
        assertEquals(raw, t.engine.encodeEvents(t.engine.decodeEvents(raw)));
        // 虎口拔牙接入（engine-0.11.0-m6a）不改变这段债务会话：换回 m3c 版本号后，以下历史黄金值逐字节不变
        // 开局道具数接入（engine-0.13.0-m6c）给房间设置加了 initialCards；旧局沿用配置（-1），去掉这个字段后与历史字节一致
        String bytes = raw.replace(",\"titles\":[],\"metrics\":[]", "").replace(com.millionnaire.engine.EngineVersion.VALUE, "engine-0.10.0-m3c").replace(CARDS_CONFIG_HASH, CONFIG_HASH)
                .replace(",\"initialCards\":-1", "").replace(",\"fastMode\":false", "");
        assertEquals(1, raw.split(java.util.regex.Pattern.quote(com.millionnaire.engine.EngineVersion.VALUE), -1).length - 1, "only the version header changes");
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
        String historicalBytes = t.engine.encodeEvents(historicalEvents).replace(",\"titles\":[],\"metrics\":[]", "").replace(CARDS_CONFIG_HASH, CONFIG_HASH)
                .replace(",\"initialCards\":-1", "").replace(",\"fastMode\":false", "")
                .replaceAll(",\"plannedDistance\":\\d+,\"stoppedBy\":null", "")
                .replace(com.millionnaire.engine.EngineVersion.VALUE, "engine-0.8.1-m3a").replace(",\"controlSource\":null", "")
                .replace(CONFIG_HASH, PREVIOUS_CONFIG_HASH);
        assertEquals("46953a081aa15740d69b5fbee5670e036bff181e77ac3130cd3e40e68135e5de",
                Canonical.sha256Hex(historicalBytes.getBytes(StandardCharsets.UTF_8)),
                "historical session behavior remains identical after removing the new source metadata");
        assertEquals(CARDS_CONFIG_HASH, t.config.contentHash());
        String m3bBytes = bytes.replaceAll(",\"plannedDistance\":\\d+,\"stoppedBy\":null", "")
                .replace("engine-0.10.0-m3c", "engine-0.9.1-m3b");
        assertEquals("a03bda62d0369e888fff87aab9f831253cd13b840dd4bc48a7f7fb37f0e3fcb3",
                Canonical.sha256Hex(m3bBytes.replace(CONFIG_HASH, PREVIOUS_CONFIG_HASH).getBytes(StandardCharsets.UTF_8)),
                "preserve the exact M3b golden after stripping only new movement metadata");
        assertEquals("7e9ceeb659ba6da5f9942726b30418df78c902c9af67b5c13cdcbf557f357b80", Canonical.sha256Hex(bytes.getBytes(StandardCharsets.UTF_8)));
    }
}
