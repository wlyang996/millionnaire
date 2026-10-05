package com.millionnaire.engine.testkit;

import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.Visibility;
import com.millionnaire.engine.time.Window;

/** 演示领域事件（测试夹具）；显式声明为公开，Peeked 另行声明为私有。 */
public sealed interface DemoEvent extends Event {

    @Override
    default Visibility visibility() {
        return Visibility.PUBLIC;
    }

    record Sat(String playerId) implements DemoEvent {
    }

    record Stood(String playerId) implements DemoEvent {
    }

    record RoundOpened(String playerId, Window window, long deadlineTaskId) implements DemoEvent {
    }

    record RoundPaused(long windowId, long at) implements DemoEvent {
    }

    record RoundResumed(long windowId, long at, long deadlineTaskId) implements DemoEvent {
    }

    record DieRolled(String playerId, long windowId, int value, boolean auto) implements DemoEvent {
    }

    record RoundAborted(long windowId, AbortReason reason) implements DemoEvent {
    }

    /** 私有结果，只发给 recipient。 */
    record Peeked(String recipient, int rollsSeen) implements DemoEvent {
        @Override
        public Visibility visibility() {
            return Visibility.PRIVATE;
        }
    }

    /** 窗口中止原因。 */
    enum AbortReason {
        STOPPED, PLAYER_LEFT
    }
}
