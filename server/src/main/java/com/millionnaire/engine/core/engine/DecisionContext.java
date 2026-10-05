package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.random.DrawPoint;
import com.millionnaire.engine.random.RandomSource;
import com.millionnaire.engine.random.RandomSourceFactory;
import com.millionnaire.engine.time.ScheduledTask;
import com.millionnaire.engine.time.TaskKind;
import java.util.ArrayList;
import java.util.List;

/**
 * 单次决策的工作区。
 * <ul>
 *   <li><b>工作状态契约</b>：每次发出事件都立即演化进工作状态，{@link #state()} 总是反映已发出事件之后的最新状态；</li>
 *   <li><b>受控入口</b>：{@link #emit} 只接受领域事件；内核事件只能经 {@link #draw}、{@link #schedule}、
 *       {@link #cancel} 产生（输入游标与任务触发由引擎写入）；</li>
 *   <li><b>随机纪律</b>：记录是否抽过随机数；抽过之后再拒绝命令属于领域程序错误，引擎报内核故障。</li>
 * </ul>
 * 决策被拒绝时引擎丢弃整个工作区，原状态不受影响。
 */
public final class DecisionContext<S extends DomainState> {
    private final Evolver<S> evolver;
    private final Class<S> stateType;
    private final RandomSourceFactory random;
    private final RuleConfig config;
    private final List<Event> events = new ArrayList<>();
    private EngineState state;
    private int draws;

    DecisionContext(EngineState start, Evolver<S> evolver, Class<S> stateType, RandomSourceFactory random,
                    RuleConfig config) {
        this.state = start;
        this.evolver = evolver;
        this.stateType = stateType;
        this.random = random;
        this.config = config;
    }

    /** 当前工作状态（含已发出事件的效果）。 */
    public EngineState engineState() {
        return state;
    }

    /** 当前领域工作状态。 */
    public S state() {
        return stateType.cast(state.domain());
    }

    /** 业务时间（处理输入时等于输入接收时间，处理任务时等于任务 dueAt）。 */
    public long now() {
        return state.now();
    }

    public RuleConfig config() {
        return config;
    }

    /** 发出领域事件并立即演化进工作状态；内核事件不得经此发出。 */
    public void emit(Event e) {
        if (e instanceof KernelEvent) {
            throw new IllegalArgumentException("kernel events must come from draw/schedule/cancel: " + e);
        }
        apply(e);
    }

    /** 抽取 [0, bound)，发出 RandomDrawn（含协议与抽取后状态）；结果须由随后的领域事件在本步内消费。 */
    public int draw(DrawPoint point, int bound) {
        RandomSource source = random.open(state.rng());
        int value = source.nextInt(point, bound);
        draws++;
        apply(new KernelEvent.RandomDrawn(random.protocolId(), point, bound, value, source.state()));
        return value;
    }

    /** 安排定时任务并返回其 ID（按工作状态顺序分配）。 */
    public long schedule(long dueAt, TaskKind kind, long ref) {
        long id = state.nextTaskId();
        apply(new KernelEvent.TaskScheduled(new ScheduledTask(id, dueAt, kind, ref)));
        return id;
    }

    public void cancel(long taskId) {
        apply(new KernelEvent.TaskCancelled(taskId));
    }

    /** 当前挂起的定时任务（按总排序）。 */
    public List<ScheduledTask> tasks() {
        return state.timers().tasks();
    }

    /** 引擎写入输入游标与任务触发事件。 */
    void emitKernel(KernelEvent e) {
        apply(e);
    }

    boolean drewRandomness() {
        return draws > 0;
    }

    List<Event> events() {
        return List.copyOf(events);
    }

    private void apply(Event e) {
        state = evolver.evolve(state, e);
        events.add(e);
    }
}
