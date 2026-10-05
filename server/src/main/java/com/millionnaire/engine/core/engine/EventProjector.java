package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.core.event.Event;
import java.util.List;

/**
 * 最小事件投影：PUBLIC 对所有人可见，PRIVATE 只对 {@link Event#recipient()} 可见，SERVER_ONLY 对任何玩家都不可见。
 * PRIVATE 事件缺少接收者时视为对所有人不可见（宁可不发，不可错发）。
 */
public final class EventProjector {
    private EventProjector() {
    }

    public static boolean visibleTo(Event event, String viewerId) {
        return switch (event.visibility()) {
            case PUBLIC -> true;
            case PRIVATE -> viewerId != null && viewerId.equals(event.recipient());
            case SERVER_ONLY -> false;
        };
    }

    public static List<Event> project(List<Event> events, String viewerId) {
        return events.stream().filter(e -> visibleTo(e, viewerId)).toList();
    }
}
