package com.millionnaire.gateway.room;

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
import com.millionnaire.gateway.config.GameConfigs;
import com.millionnaire.gateway.record.GameRecords;
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
import org.springframework.beans.factory.annotation.Autowired;
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

    /** 游戏参数发布版本：建房时取当前生效版本，房间整局沿用（对局中途发布不影响已开的房间）。 */
    private final GameConfigs configs;
    /** 昵称校验用的引擎（加入规则与参数无关）。 */
    private final Engine<SessionState> engine;
    private final EngineState nicknameProbe;
    private final Map<Integer, LiveRoom> byCode = new ConcurrentHashMap<>();
    private final Map<String, LiveRoom> byPlayer = new ConcurrentHashMap<>();
    /** 玩家所选头像（序号 0～7）；没选的不在表里，客户端按玩家 ID 取默认头像。只用于显示，不进引擎状态。 */
    private final Map<String, Integer> avatars = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers;
    private final RoomStore store;
    private final Outbox outbox;
    private final Wire wire;
    private final Clock clock;
    private final GameRecords records;
    private final Presence presence;
    /** 连接判定的观测序号：单调递增（引擎拒绝不大于已采纳序号的过期判定）。 */
    private final java.util.concurrent.atomic.AtomicLong observations = new java.util.concurrent.atomic.AtomicLong();

    /** 不连数据库的测试用：战绩只在内存；不自动做连接判定（测试里手动调用 {@link #checkConnections()}）。 */
    public RoomService(RoomStore store, Outbox outbox, Wire wire, Clock clock) {
        this(store, outbox, wire, clock, GameRecords.inMemory(clock), new Presence(), GameConfigs.inMemory(clock), false);
    }

    /** 不连数据库的测试用，指定参数版本库（测试发布新版本后建房）。 */
    public RoomService(RoomStore store, Outbox outbox, Wire wire, Clock clock, GameConfigs configs) {
        this(store, outbox, wire, clock, GameRecords.inMemory(clock), new Presence(), configs, false);
    }

    @Autowired
    public RoomService(RoomStore store, Outbox outbox, Wire wire, Clock clock, GameRecords records, Presence presence,
                       GameConfigs configs) {
        this(store, outbox, wire, clock, records, presence, configs, true);
    }

    private RoomService(RoomStore store, Outbox outbox, Wire wire, Clock clock, GameRecords records, Presence presence,
                        GameConfigs configs, boolean monitorConnections) {
        this.configs = configs;
        this.engine = configs.byId(GameConfigs.DEFAULT_ID).orElseThrow().engine();
        this.records = records;
        this.presence = presence;
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
        if (monitorConnections) {
            timers.scheduleWithFixedDelay(this::checkConnections, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** 每秒：各房间按最后收到消息的时刻判定疑似断线 / 确认掉线 / 重连，以及全员掉线中止。 */
    public void checkConnections() {
        long now = now();
        for (LiveRoom room : byCode.values()) {
            try {
                room.checkConnections(now, presence, observations::incrementAndGet);
            } catch (RuntimeException e) {
                log.error("room {} connection check failed", room.code(), e);
            }
        }
    }

    /** 测试用：连接判定读取的在线记录。 */
    Presence presence() {
        return presence;
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

    /** 记下玩家所选头像（建房、加房、加机器人、连上 WebSocket 时调用）。 */
    public void rememberAvatar(User user) {
        if (user.avatar() >= 0) {
            avatars.put(user.playerId(), user.avatar());
        }
    }

    /** 这些玩家里选过头像的：玩家 ID → 头像序号（随 UPDATE 推送）。 */
    public Map<String, Integer> avatarsOf(java.util.Collection<String> players) {
        Map<String, Integer> out = new java.util.TreeMap<>();
        for (String p : players) {
            Integer a = avatars.get(p);
            if (a != null) {
                out.put(p, a);
            }
        }
        return out;
    }

    public synchronized Created create(User user, String requestId, RoomSettings settings) {
        rememberAvatar(user);
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
            GameConfigs.Active cfg = configs.current();
            EngineState genesis = cfg.engine().create(Long.toString(roomId), Ids.seed(), now).state();
            room = new LiveRoom(this, cfg.engine(), cfg.configId(), roomId, code, pid, requestId, genesis, now);
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
        rememberAvatar(user);
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

    /** 房主给自己的房间加一个测试机器人（机器人是一个新建的测试用户）。 */
    public LiveRoom.Reply addBot(User host, String requestId, User bot) {
        rememberAvatar(bot);
        LiveRoom room = byPlayer.get(host.playerId());
        if (room == null) {
            throw new ClientException("NOT_IN_ROOM", "join or create a room first");
        }
        return room.addBot(host.playerId(), requestId, bot.playerId(), bot.nickname());
    }

    /** 地图模板（格子类型、档位、指定拍卖地、人数容量），客户端据此画棋盘，不自己写死布局。 */
    public java.util.List<com.millionnaire.engine.config.BoardTemplate> boards() {
        return engine.config().boards();
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

    /** 局结束：提交战绩草稿（异步落库）。 */
    void gameEnded(GameRecords.Draft draft) {
        records.submit(draft);
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
