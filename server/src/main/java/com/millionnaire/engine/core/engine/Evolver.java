package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.KernelEvent.Genesis;
import com.millionnaire.engine.core.event.KernelEvent.InputAccepted;
import com.millionnaire.engine.core.event.KernelEvent.InputRejected;
import com.millionnaire.engine.core.event.KernelEvent.RandomDrawn;
import com.millionnaire.engine.core.event.KernelEvent.TaskCancelled;
import com.millionnaire.engine.core.event.KernelEvent.TaskFired;
import com.millionnaire.engine.core.event.KernelEvent.TaskScheduled;
import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.random.Draw;
import com.millionnaire.engine.serialize.Immutable;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TimerQueue;
import java.util.List;

/**
 * evolve：纯函数 (状态, 事件) → 新状态。内核事件在此处理，领域事件交给 {@link Domain#evolve}。
 * 不做规则判定，只做一致性断言；断言失败说明日志与状态不匹配，抛 {@link IllegalStateException}。
 */
final class Evolver<S extends DomainState> {
    private final Domain<S> domain;
    private final RuleConfig rules;

    /** rules：本局绑定的不可变规则（引擎以配置哈希保证与状态一致）。 */
    Evolver(Domain<S> domain, RuleConfig rules) {
        this.domain = domain;
        this.rules = rules;
    }

    EngineState evolveAll(EngineState state, List<Event> events) {
        EngineState s = state;
        for (Event e : events) {
            s = evolve(s, e);
        }
        return s;
    }

    EngineState evolve(EngineState s, Event event) {
        if (event instanceof Genesis g) {
            check(s == null, "Genesis must be the first event");
            check(domain.id().equals(g.domainId()) && domain.stateType().isInstance(g.initial()),
                    "genesis belongs to another domain: " + g.domainId());
            return new EngineState(g.roomId(), g.configHash(), g.engineVersion(), g.domainId(), g.rngProtocol(),
                    g.at(), 0, g.at(), null, 1, TimerQueue.empty(), 1, g.rng(), List.of(), g.initial());
        }
        check(s != null, "first event must be Genesis");
        EngineState next = event instanceof KernelEvent k ? kernel(s, k) : domainEvent(s, event);
        return next.withEventCount(Math.addExact(s.eventCount(), 1));
    }

    private EngineState kernel(EngineState s, KernelEvent event) {
        return switch (event) {
            case Genesis g -> throw new IllegalStateException("unreachable");
            case InputAccepted e -> {
                checkStepBoundary(s);
                domain.checkBoundary(domain.stateType().cast(s.domain()));
                check(e.seq() == Math.addExact(s.lastSeq(), 1), "input seq must be lastSeq+1");
                check(e.at() >= s.lastReceivedAt() && e.at() >= s.now(), "accepted input must not go back in time");
                yield s.withInputCursor(e.seq(), e.at(), e.digest()).withNow(e.at());
            }
            case InputRejected e -> {
                checkStepBoundary(s);
                domain.checkBoundary(domain.stateType().cast(s.domain()));
                check(e.seq() == Math.addExact(s.lastSeq(), 1) && e.code() != null, "input seq must be lastSeq+1");
                yield s.withInputCursor(e.seq(), Math.max(s.lastReceivedAt(), e.at()), e.digest());
            }
            case TaskScheduled e -> {
                ScheduledTask t = e.task();
                check(t.taskId() == s.nextTaskId(), "task id must be allocated in order");
                check(t.dueAt() > s.now(), "task must be due in the future");
                yield s.withTimers(s.timers().schedule(t), Math.addExact(t.taskId(), 1));
            }
            case TaskCancelled e -> s.withTimers(s.timers().cancel(e.taskId()), s.nextTaskId());
            case TaskFired e -> {
                checkStepBoundary(s);
                domain.checkBoundary(domain.stateType().cast(s.domain()));
                ScheduledTask head = s.timers().peek().orElseThrow(() -> new IllegalStateException("no task to fire"));
                check(head.taskId() == e.taskId() && head.dueAt() == e.at() && e.at() >= s.now(),
                        "fired task must be the queue head");
                yield s.withTimers(s.timers().cancel(e.taskId()), s.nextTaskId()).withNow(e.at());
            }
            case RandomDrawn e -> {
                check(s.rngProtocol().equals(e.protocol()), "draw uses protocol " + e.protocol());
                check(e.after() != null, "draw must record the resulting state");
                Draw d = new Draw(e.point(), e.bound(), e.value());
                yield s.withRandom(e.after(), Immutable.append(s.pendingDraws(), d));
            }
        };
    }

    private EngineState domainEvent(EngineState s, Event event) {
        Draws draws = new Draws(s.pendingDraws());
        S next = domain.evolve(domain.stateType().cast(s.domain()), event, draws, rules);
        check(next != null, "domain returned no state");
        return s.withDomain(next).withRandom(s.rng(), draws.remaining());
    }

    /** 步边界（新输入或新任务开始处理）时，上一步的随机结果必须已全部被消费。 */
    private static void checkStepBoundary(EngineState s) {
        check(s.pendingDraws().isEmpty(), "unconsumed random draws: " + s.pendingDraws());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
