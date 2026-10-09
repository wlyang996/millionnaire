package com.millionnaire.gateway.record;

import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.engine.Engine;
import com.millionnaire.engine.core.engine.SessionDomain;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.state.SessionState;
import java.util.List;

/** 解码 game_log 里的事件日志（引擎 Codec 规范文本；解码不校验配置版本）。数据看板与战绩详情共用。 */
public final class EventLogs {
    private static volatile Engine<SessionState> decoder;

    private EventLogs() {
    }

    public static List<Event> decode(String text) {
        Engine<SessionState> d = decoder;
        if (d == null) {
            d = new Engine<>(RuleConfigs.defaultV1(), SessionDomain.INSTANCE);
            decoder = d;
        }
        return d.decodeEvents(text);
    }
}
