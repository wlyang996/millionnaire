package com.millionnaire.engine.core.event;

import com.millionnaire.engine.core.state.RoomSettings;

/** 大厅事件（全部公开）。 */
public sealed interface RoomEvent extends Event {

    @Override
    default Visibility visibility() {
        return Visibility.PUBLIC;
    }

    record PlayerJoined(String playerId, String nickname) implements RoomEvent {
    }

    record PlayerLeft(String playerId, LeaveReason reason) implements RoomEvent {
    }

    record HostChanged(String hostId) implements RoomEvent {
    }

    record ReadyChanged(String playerId, boolean ready) implements RoomEvent {
    }

    /** 设置修改；演化时全员取消准备（requirements 第 2 节）。 */
    record SettingsChanged(RoomSettings settings) implements RoomEvent {
    }

    record RoomClosed() implements RoomEvent {
    }

    /** 离开原因。 */
    enum LeaveReason {
        LEFT, KICKED
    }
}
