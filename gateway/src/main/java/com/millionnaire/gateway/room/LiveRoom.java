package com.millionnaire.gateway.room;

import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.Input;
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
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.Member;
import com.millionnaire.engine.core.state.RoomState;
import com.millionnaire.engine.core.state.RoomStatus;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.SessionView;
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
 * </ul>
 */
public final class LiveRoom {
    private static final Logger log = LoggerFactory.getLogger(LiveRoom.class);
    private static final int MAX_REPLIES = 512;

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

    synchronized void wakeUp(long dueAt) {
        if (closed || dueAt != wakeAt) {
            return; // 已被更新的唤醒取代
        }
        wake = null;
        wakeAt = Long.MIN_VALUE;
        step(new Tick(), Math.max(service.now(), dueAt));
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
        return r;
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
