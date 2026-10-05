package com.millionnaire.engine.core.command;

import com.millionnaire.engine.core.state.RoomSettings;

/** 房间命令（requirements 第 2 节）。 */
public sealed interface RoomCommand extends Command {

    record Join(String playerId, String nickname) implements RoomCommand {
        @Override
        public String actor() {
            return playerId;
        }
    }

    record Leave(String playerId) implements RoomCommand {
        @Override
        public String actor() {
            return playerId;
        }
    }

    record Kick(String actor, String target) implements RoomCommand {
    }

    record SetReady(String playerId, boolean ready) implements RoomCommand {
        @Override
        public String actor() {
            return playerId;
        }
    }

    record ChangeSettings(String actor, RoomSettings settings) implements RoomCommand {
    }
}
