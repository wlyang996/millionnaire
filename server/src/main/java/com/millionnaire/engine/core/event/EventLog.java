package com.millionnaire.engine.core.event;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** 事件日志的规范序列化载体（由引擎包上格式信封后持久化）。 */
public record EventLog(List<Event> events) {
    public EventLog {
        events = Immutable.list(events);
    }
}
