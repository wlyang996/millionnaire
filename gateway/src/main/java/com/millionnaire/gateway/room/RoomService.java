package com.millionnaire.gateway.room;

import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand.ChangeSettings;
import com.millionnaire.engine.core.command.RoomCommand.Join;
import com.millionnaire.engine.core.engine.Engine;
import com.millionnaire.engine.core.engine.SessionDomain;
import com.millionnaire.engine.core.engine.StepResult;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.RoomSettings;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.gateway.auth.Ids;
import com.millionnaire.gateway.auth.UserStore.User;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 房间注册表：房间号 → 房间，玩家 → 所在房间（每人同时只在一个房间）。
 * 锁顺序：本服务的 synchronized（建房、加入）在外，房间的 synchronized 在内；房间回调本服务时只碰并发容器，不取本服务的锁。
 */
@Service
public class RoomService {
    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    /** 建房结果：房间，以及（带了设置时）修改设置的结论。 */
    public record Created(LiveRoom room, LiveRoom.Reply settings) {
    }

    private final Engine<SessionState> engine = new Engine<>(RuleConfigs.defaultV1(), SessionDomain.INSTANCE);
    private final EngineState nicknameProbe;
    private final Map<Integer, LiveRoom> byCode = new ConcurrentHashMap<>();
    private final Map<String, LiveRoom> byPlayer = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers;
    private final RoomStore store;
    private final Outbox outbox;
    private final Wire wire;
    private final Clock clock;

    public RoomService(RoomStore store, Outbox outbox, Wire wire, Clock clock) {
        this.store = store;
        this.outbox = outbox;
        this.wire = wire;
        this.clock = clock;
        this.nicknameProbe = engine.create("nickname-probe", 0, 0).state();
        this.timers = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "room-timer");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        timers.shutdownNow();
    }

    /** 昵称是否合法：直接用引擎的加入规则判定（与开房后加入时的校验完全一致）。 */
    public boolean validNickname(String nickname) {
        StepResult r = engine.step(nicknameProbe,
                new Input(nicknameProbe.lastSeq() + 1, 0, new Join("probe", nickname)));
        return r.outcome() == StepResult.Outcome.ACCEPTED;
    }

    public synchronized Created create(User user, String requestId, RoomSettings settings) {
        String pid = user.playerId();
        LiveRoom current = byPlayer.get(pid);
        if (current != null) {
            if (current.createdBy(pid, requestId)) {
                return new Created(current, null); // 同一请求重发
            }
            throw new ClientException("ALREADY_IN_ROOM", "leave room " + current.code() + " first");
        }
        RoomStore.Opened opened = store.open(user.id(), Ids.digest16(requestId));
        long roomId = opened.roomId();
        int code = opened.code();
        long now = now();
        LiveRoom room;
        try {
            EngineState genesis = engine.create(Long.toString(roomId), Ids.seed(), now).state();
            room = new LiveRoom(this, engine, roomId, code, pid, requestId, genesis, now);
        } catch (RuntimeException e) {
            store.close(roomId, code, "FAULT"); // 不留孤儿 OPEN 行
            throw e;
        }
        byCode.put(code, room);
        LiveRoom.Reply join = room.submitClient(pid, requestId + "#join", new Join(pid, user.nickname()));
        if (!join.ok()) {
            room.abandon("ENGINE");
            throw new ClientException(join.code() == null ? "JOIN_FAILED" : join.code(), "cannot join the new room");
        }
        LiveRoom.Reply s = settings == null ? null
                : room.submitClient(pid, requestId + "#settings", new ChangeSettings(pid, settings));
        log.info("room {} created by {}", room.code(), pid);
        return new Created(room, s);
    }

    public synchronized LiveRoom.Reply join(User user, int code, String requestId) {
        String pid = user.playerId();
        LiveRoom room = byCode.get(code);
        if (room == null) {
            throw new ClientException("ROOM_NOT_FOUND", "no open room with this code");
        }
        LiveRoom current = byPlayer.get(pid);
        if (current != null && current != room) {
            throw new ClientException("ALREADY_IN_ROOM", "leave room " + current.code() + " first");
        }
        return room.submitClient(pid, requestId, new Join(pid, user.nickname()));
    }

    public Optional<LiveRoom> roomOf(String playerId) {
        return Optional.ofNullable(byPlayer.get(playerId));
    }

    public Optional<LiveRoom> byCode(int code) {
        return Optional.ofNullable(byCode.get(code));
    }

    // ------------------------------------------------------------ 房间回调（在房间锁内调用）

    void membersChanged(LiveRoom room, Set<String> before, Set<String> after) {
        for (String p : after) {
            byPlayer.put(p, room);
        }
        for (String p : before) {
            if (!after.contains(p)) {
                byPlayer.remove(p, room);
            }
        }
    }

    void closed(LiveRoom room, String reason, Set<String> notify) {
        byCode.remove(room.codeNumber(), room);
        byPlayer.values().removeIf(r -> r == room);
        try {
            store.close(room.roomId(), room.codeNumber(), reason);
        } catch (RuntimeException e) {
            log.error("cannot mark room {} closed", room.code(), e);
        }
        String msg = wire.closed(room.code(), reason);
        notify.forEach(p -> outbox.send(p, msg));
        log.info("room {} closed: {}", room.code(), reason);
    }

    void wake(LiveRoom room, long dueAt) {
        try {
            room.wakeUp(dueAt);
        } catch (ClientException e) {
            log.warn("room {} wake-up failed: {}", room.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("room {} wake-up failed", room.code(), e);
        }
    }

    long now() {
        return clock.millis();
    }

    ScheduledExecutorService timers() {
        return timers;
    }

    Outbox outbox() {
        return outbox;
    }

    Wire wire() {
        return wire;
    }
}
