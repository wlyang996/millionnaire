package com.millionnaire.engine.testkit;

import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.PublicEvent;

/** 假模块的日志事件（自动动作、全局到时）。 */
public sealed interface FlowTestEvent extends Event {
    record Logged(String line) implements FlowTestEvent, PublicEvent {
    }
}
