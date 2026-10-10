package com.millionnaire.engine.core.state;

/** 房间成员；成员列表按加入先后排列。 */
public record Member(String playerId, String nickname, boolean ready, boolean bot) {
    public Member(String playerId, String nickname, boolean ready) { this(playerId, nickname, ready, false); }
    public Member withReady(boolean value) {
        return new Member(playerId, nickname, value, bot);
    }
}
