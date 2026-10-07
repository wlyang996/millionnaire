package com.millionnaire.gateway.room;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.GameCommand.SetControl;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.RoomCommand.Join;
import com.millionnaire.engine.core.command.RoomCommand.Leave;
import com.millionnaire.engine.core.command.RoomCommand.SetReady;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.engine.Engine;
import com.millionnaire.engine.core.engine.EventProjector;
import com.millionnaire.engine.core.engine.InputOrderException;
import com.millionnaire.engine.core.engine.InvalidInputException;
import com.millionnaire.engine.core.engine.KernelFaultException;
import com.millionnaire.engine.core.engine.RoomHaltedException;
import com.millionnaire.engine.core.engine.RoomRunner;
import com.millionnaire.engine.core.engine.SessionDomain;
import com.millionnaire.engine.core.engine.StateValidationException;
import com.millionnaire.engine.core.engine.StepResult;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.config.EndMode;
import com.millionnaire.engine.core.event.GameEvent.DiceRolled;
import com.millionnaire.engine.core.event.GameEvent.GameEnded;
import com.millionnaire.engine.core.event.GameEvent.PlayerEliminated;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.Standing;
import com.millionnaire.gateway.record.GameRecords;
import com.millionnaire.engine.core.event.GameEvent.JailRolled;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.state.ConnState;
import com.millionnaire.engine.core.state.ControlMode;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.Member;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.RoomState;
import com.millionnaire.engine.core.state.RoomStatus;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.SessionView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个进行中的房间：持有引擎的最后已提交状态（{@link RoomRunner}），本房间的输入全部经 synchronized 串行化。
 * <ul>
 *   <li>接收序号 = 上一步序号 + 1，接收时间取服务器时钟且单调不减；</li>
 *   <li>每步之后按 {@link Engine#nextWakeUp} 安排一次 Tick（回合超时、自动动作等由引擎在 Tick 中处理）；</li>
 *   <li>每步有领域事件时，给本步前后在房间里的每个人推送：对他可见的事件 + 他的最新视图；</li>
 *   <li>同一玩家的同一 requestId 只处理一次，重发时原样返回上次的结论；</li>
 *   <li>内核故障：房间停止并关闭（不自动重试），对局状态只在内存，不做恢复。</li>
 *   <li>挂机判定：手动玩家连续 {@value #AFK_MISSED_ROLLS} 次让投骰窗口超时（由系统代投），服务端用可信系统命令把他转为暂离（挂机），
 *       需要他本人点"我回来了"（或做任何业务操作）才恢复；全员挂机 / 托管时引擎会结束对局。</li>
 * </ul>
 */
public final class LiveRoom {
    private static final Logger log = LoggerFactory.getLogger(LiveRoom.class);
    private static final int MAX_REPLIES = 512;
    private static final int CHAT_KEEP = 30;
    private static final int CHAT_MAX_CHARS = 40;
    private static final long CHAT_MIN_GAP_MS = 800;
    static final int AFK_MISSED_ROLLS = 2;

    /** 一条命令的处理结论；code 为引擎的拒绝原因（ACCEPTED 时为 null）。 */
    public record Reply(String outcome, String code) {
        public boolean ok() {
            return "ACCEPTED".equals(outcome);
        }

        static Reply of(StepResult r) {
            return new Reply(r.outcome().name(), r.rejection() == null ? null : r.rejection().name());
        }
    }

    private final RoomService service;
    private final Engine<SessionState> engine;
    private final RoomRunner<SessionState> runner;
    private final long roomId;
    private final int code;
    private final String createdBy;
    private final String createRequestId;
    private final Map<String, Reply> replies = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Reply> eldest) {
            return size() > MAX_REPLIES;
        }
    };
    /** 最近的聊天（只在内存，不进引擎、不落库）。 */
    private final Deque<ObjectNode> chat = new ArrayDeque<>();
    private final Map<String, Long> lastChatAt = new HashMap<>();
    /** 挂机判定：每位手动玩家连续被系统代投的次数（亲手投骰即清零）。 */
    private final Map<String, Integer> missedRolls = new HashMap<>();
    /** 测试机器人（房主在测试环境添加）：在房间里自动准备，开局后转为托管，由引擎的自动动作代打。 */
    private final Set<String> bots = new LinkedHashSet<>();
    private boolean tendingBots;
    private ScheduledFuture<?> wake;
    private long wakeAt = Long.MIN_VALUE;
    private long lastTime;
    private boolean closed;

    LiveRoom(RoomService service, Engine<SessionState> engine, long roomId, int code, String createdBy,
             String createRequestId, EngineState genesis, long at) {
        this.service = service;
        this.engine = engine;
        this.roomId = roomId;
        this.code = code;
        this.createdBy = createdBy;
        this.createRequestId = createRequestId;
        this.runner = new RoomRunner<>(engine, genesis);
        this.lastTime = at;
    }

    public long roomId() {
        return roomId;
    }

    public int codeNumber() {
        return code;
    }

    /** 展示用的六位房间号（补零）。 */
    public String code() {
        return String.format("%06d", code);
    }

    boolean createdBy(String playerId, String requestId) {
        return createdBy.equals(playerId) && createRequestId.equals(requestId);
    }

    /** 客户端命令（actor 已由外层从登录令牌写入）。 */
    public synchronized Reply submitClient(String playerId, String requestId, Command command) {
        String key = playerId + '\u0000' + requestId;
        Reply previous = replies.get(key);
        if (previous != null) {
            return previous;
        }
        if (closed) {
            throw new ClientException("ROOM_CLOSED", "room is closed");
        }
        try {
            engine.admitClient(command);
        } catch (InvalidInputException e) {
            throw new ClientException("BAD_REQUEST", e.getMessage());
        }
        Reply r = Reply.of(step(command, service.now()));
        replies.put(key, r);
        return r;
    }

    /** 服务端产生的可信系统命令（如托管切换）。 */
    public synchronized Reply submitSystem(Command command) {
        if (closed) {
            throw new ClientException("ROOM_CLOSED", "room is closed");
        }
        try {
            engine.admitSystem(command);
        } catch (InvalidInputException e) {
            throw new ClientException("BAD_REQUEST", e.getMessage());
        }
        return Reply.of(step(command, service.now()));
    }

    /**
     * 房主添加一个测试机器人（仅测试登录开启时由外层调用）：机器人以自己的身份加入并准备。
     * 同一房主的同一 requestId 只处理一次。
     */
    public synchronized Reply addBot(String hostId, String requestId, String botId, String nickname) {
        String key = hostId + '\u0000' + requestId;
        Reply previous = replies.get(key);
        if (previous != null) {
            return previous;
        }
        if (closed) {
            throw new ClientException("ROOM_CLOSED", "room is closed");
        }
        RoomState lobby = lobby(runner.committed());
        if (!lobby.isHost(hostId)) {
            throw new ClientException("NOT_HOST", "only the host can add bots");
        }
        if (((SessionState) runner.committed().domain()).inGame()) {
            throw new ClientException("IN_GAME", "cannot add bots during a game");
        }
        Command join = new Join(botId, nickname);
        try {
            engine.admitClient(join);
        } catch (InvalidInputException e) {
            throw new ClientException("BAD_REQUEST", e.getMessage());
        }
        bots.add(botId); // 先登记，加入那一步之后的照看会让它准备
        Reply r = Reply.of(step(join, service.now()));
        if (!r.ok()) {
            bots.remove(botId);
        }
        replies.put(key, r);
        return r;
    }

    synchronized void wakeUp(long dueAt) {
        if (closed || dueAt != wakeAt) {
            return; // 已被更新的唤醒取代
        }
        wake = null;
        wakeAt = Long.MIN_VALUE;
        step(new Tick(), Math.max(service.now(), dueAt));
    }

    /**
     * 聊天：房间成员（含破产观战者）发一句话，推给房间里的每个人。
     * 推送内容是最近 {@value #CHAT_KEEP} 条的完整列表，客户端直接替换即可（重连后也能补齐）。
     */
    public synchronized void chat(String playerId, String nickname, String raw) {
        if (closed) {
            throw new ClientException("ROOM_CLOSED", "room is closed");
        }
        if (!lobby(runner.committed()).isMember(playerId)) {
            throw new ClientException("NOT_IN_ROOM", "not a member of this room");
        }
        String text = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").strip();
        if (text.isEmpty()) {
            throw new ClientException("BAD_REQUEST", "empty message");
        }
        if (text.codePointCount(0, text.length()) > CHAT_MAX_CHARS) {
            text = text.substring(0, text.offsetByCodePoints(0, CHAT_MAX_CHARS));
        }
        long now = service.now();
        Long last = lastChatAt.get(playerId);
        if (last != null && now - last < CHAT_MIN_GAP_MS) {
            throw new ClientException("TOO_FAST", "slow down");
        }
        lastChatAt.put(playerId, now);
        chat.addLast(service.wire().object().put("from", playerId).put("nickname", nickname)
                .put("text", text).put("at", now));
        while (chat.size() > CHAT_KEEP) {
            chat.removeFirst();
        }
        String json = chatMessage();
        for (String p : members(runner.committed())) {
            service.outbox().send(p, json);
        }
    }

    /** 最近聊天的完整列表（连接、重连时补发）。 */
    public synchronized String chatMessage() {
        ObjectNode m = service.wire().object().put("type", "CHAT").put("roomCode", code());
        ArrayNode lines = m.putArray("lines");
        chat.forEach(lines::add);
        return service.wire().write(m);
    }

    public synchronized SessionView view(String playerId) {
        return SessionDomain.INSTANCE.project(runner.committed(), playerId);
    }

    /** 给某个观察者的完整快照（重连、SYNC 时用）。 */
    public synchronized String snapshot(String playerId) {
        EngineState s = runner.committed();
        return service.wire().update(code(), s.lastSeq(), service.now(), List.of(),
                SessionDomain.INSTANCE.project(s, playerId));
    }

    public synchronized boolean isMember(String playerId) {
        return lobby(runner.committed()).isMember(playerId);
    }

    public synchronized OptionalLong gameNo() {
        SessionState s = (SessionState) runner.committed().domain();
        return s.inGame() ? OptionalLong.of(s.game().gameNo()) : OptionalLong.empty();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    synchronized long scheduledWakeAt() {
        return wakeAt;
    }

    /** 建房失败时丢弃（尚无其他成员）。 */
    synchronized void abandon(String reason) {
        shutDown(reason, Set.of());
    }

    private StepResult step(Command command, long now) {
        long at = Math.max(now, lastTime);
        EngineState before = runner.committed();
        Set<String> membersBefore = members(before);
        StepResult r;
        try {
            r = runner.submit(new Input(before.lastSeq() + 1, at, command));
        } catch (KernelFaultException | RoomHaltedException | InputOrderException | StateValidationException e) {
            log.error("room {} halted at seq {}", code(), before.lastSeq() + 1, e);
            shutDown("FAULT", membersBefore);
            throw new ClientException("ROOM_HALTED", "room stopped because of a server error");
        }
        lastTime = at;
        EngineState after = r.state();
        Set<String> membersAfter = members(after);
        // 先做簿记（成员索引、关房或安排下一次唤醒），推送失败不能影响房间推进
        service.membersChanged(this, membersBefore, membersAfter);
        if (lobby(after).status() == RoomStatus.CLOSED) {
            shutDown("ENGINE", Set.of());
        } else {
            schedule(after);
        }
        if (r.events().stream().anyMatch(e -> !(e instanceof KernelEvent))) {
            // 离开或被踢的人也收到这一步（含自己的 PlayerLeft），之后不再推送
            Set<String> audience = new LinkedHashSet<>(membersBefore);
            audience.addAll(membersAfter);
            for (String p : audience) {
                try {
                    List<Event> visible = EventProjector.project(r.events(), p);
                    service.outbox().send(p, service.wire().update(code(), after.lastSeq(), at, visible,
                            SessionDomain.INSTANCE.project(after, p)));
                } catch (RuntimeException e) {
                    log.error("room {} cannot push seq {} to {}", code(), after.lastSeq(), p, e);
                }
            }
        }
        recordIfEnded(before, r.events(), at);
        markAway(missedRolls(r.events(), after), at);
        tendBots(at);
        return r;
    }

    /**
     * 照看测试机器人（每步之后）：不在房间的移除；只剩机器人时全部离开（房间随之关闭）；
     * 大厅里自动准备；对局中存活且仍为手动的转为托管。照看产生的步骤不再递归照看。
     */
    private void tendBots(long at) {
        if (bots.isEmpty() || tendingBots || closed) {
            return;
        }
        tendingBots = true;
        try {
            Set<String> present = members(runner.committed());
            bots.retainAll(present);
            boolean humans = present.stream().anyMatch(p -> !bots.contains(p));
            for (String bot : List.copyOf(bots)) {
                if (closed) {
                    return;
                }
                EngineState s = runner.committed();
                SessionState session = (SessionState) s.domain();
                Command c = null;
                if (!humans) {
                    c = new Leave(bot);
                } else if (session.inGame()) {
                    PlayerState ps = session.game().player(bot).orElse(null);
                    if (ps != null && ps.alive() && ps.control() == ControlMode.MANUAL) {
                        c = new SetControl(session.game().gameNo(), bot, ControlMode.HOSTED);
                    }
                } else if (lobby(s).status() == RoomStatus.OPEN
                        && lobby(s).member(bot).map(m -> !m.ready()).orElse(false)) {
                    c = new SetReady(bot, true);
                }
                if (c == null) {
                    continue;
                }
                try {
                    if (c instanceof SetControl) {
                        engine.admitSystem(c);
                    } else {
                        engine.admitClient(c);
                    }
                    step(c, at);
                } catch (RuntimeException e) {
                    log.warn("room {}: bot {} step failed: {}", code(), bot, e.getMessage());
                }
            }
        } finally {
            tendingBots = false;
        }
    }

    public synchronized int memberCount() {
        return lobby(runner.committed()).members().size();
    }

    /** 是否为本房间的测试机器人。 */
    public synchronized boolean isBot(String playerId) {
        return bots.contains(playerId);
    }

    /**
     * 本步有 GameEnded：按开局座位顺序生成战绩草稿交给外层落库。名次、净资产、现金取自终局结算；
     * 生死状态取本步之前的状态，再叠加本步里的淘汰事件（如最后一人认输导致结束）。非数字的玩家 ID 不记录。
     */
    private void recordIfEnded(EngineState before, List<Event> events, long at) {
        SessionState s = (SessionState) before.domain();
        if (!s.inGame()) {
            return;
        }
        for (Event e : events) {
            if (!(e instanceof GameEnded ended)) {
                continue;
            }
            GameState g = s.game();
            Map<String, String> life = new HashMap<>();
            for (PlayerState p : g.players()) {
                life.put(p.playerId(), p.life().name());
            }
            for (Event x : events) {
                if (x instanceof PlayerEliminated pe) {
                    life.put(pe.playerId(), pe.life().name());
                }
            }
            Map<String, Standing> standings = new HashMap<>();
            for (Standing st : ended.result().standings()) {
                standings.put(st.playerId(), st);
            }
            List<GameRecords.Seat> seats = new ArrayList<>();
            List<PlayerState> players = g.players();
            for (int i = 0; i < players.size(); i++) {
                String pid = players.get(i).playerId();
                long uid;
                try {
                    uid = Long.parseLong(pid);
                } catch (NumberFormatException nfe) {
                    continue;
                }
                Standing st = standings.get(pid);
                seats.add(new GameRecords.Seat(i, uid, st == null ? null : st.rank(), st == null ? null : st.netWorth(),
                        st == null ? null : st.cash(), life.get(pid)));
            }
            var set = g.settings();
            boolean timed = set.endMode() == EndMode.TIME_LIMIT;
            service.gameEnded(new GameRecords.Draft(roomId, ended.gameNo(), ended.reason(), set.endMode().name(),
                    timed ? set.timeLimitMinutes() : null, set.boardId(), set.initialCash(), g.startedAt(),
                    Math.max(at, g.startedAt()), service.configHash(), seats));
        }
    }

    /** 本步里被系统代投的手动玩家计数；达到阈值的玩家返回（计数清零）。亲手投骰、本来就由系统代管的清零。 */
    private List<String> missedRolls(List<Event> events, EngineState after) {
        SessionState s = (SessionState) after.domain();
        if (!s.inGame()) {
            missedRolls.clear();
            return List.of();
        }
        List<String> away = new ArrayList<>();
        for (Event e : events) {
            String p;
            boolean auto;
            if (e instanceof DiceRolled d) {
                p = d.playerId();
                auto = d.auto();
            } else if (e instanceof JailRolled j) {
                p = j.playerId();
                auto = j.auto();
            } else {
                continue;
            }
            PlayerState ps = s.game().player(p).orElse(null);
            if (ps == null || !auto || !ps.alive() || ps.control() != ControlMode.MANUAL || ps.conn() == ConnState.OFFLINE) {
                missedRolls.remove(p);
                continue;
            }
            if (missedRolls.merge(p, 1, Integer::sum) >= AFK_MISSED_ROLLS) {
                missedRolls.remove(p);
                away.add(p);
            }
        }
        return away;
    }

    /** 判定挂机：以可信系统命令转为暂离（与玩家自己选"暂离"走同一条命令，结果随推送下发）。 */
    private void markAway(List<String> players, long at) {
        for (String p : players) {
            SessionState s = (SessionState) runner.committed().domain();
            if (closed || !s.inGame()) {
                return;
            }
            Command c = new SetControl(s.game().gameNo(), p, ControlMode.AWAY);
            try {
                engine.admitSystem(c);
                StepResult r = step(c, at);
                log.info("room {}: {} missed {} rolls in a row, marked away ({})", code(), p, AFK_MISSED_ROLLS, r.outcome());
            } catch (RuntimeException e) {
                log.warn("room {}: cannot mark {} away: {}", code(), p, e.getMessage());
            }
        }
    }

    private void schedule(EngineState state) {
        OptionalLong due = engine.nextWakeUp(state);
        if (due.isPresent() && due.getAsLong() == wakeAt) {
            return;
        }
        cancelWake();
        if (due.isPresent()) {
            long dueAt = due.getAsLong();
            try {
                wake = service.timers().schedule(() -> service.wake(this, dueAt),
                        Math.max(0, dueAt - service.now()), TimeUnit.MILLISECONDS);
                wakeAt = dueAt;
            } catch (RejectedExecutionException e) {
                log.warn("room {} wake-up not scheduled (shutting down)", code());
            }
        }
    }

    private void cancelWake() {
        if (wake != null) {
            wake.cancel(false);
        }
        wake = null;
        wakeAt = Long.MIN_VALUE;
    }

    private void shutDown(String reason, Set<String> notify) {
        if (closed) {
            return;
        }
        closed = true;
        cancelWake();
        service.closed(this, reason, notify);
    }

    private static RoomState lobby(EngineState s) {
        return ((SessionState) s.domain()).lobby();
    }

    private static Set<String> members(EngineState s) {
        Set<String> ids = new LinkedHashSet<>();
        for (Member m : lobby(s).members()) {
            ids.add(m.playerId());
        }
        return ids;
    }
}
