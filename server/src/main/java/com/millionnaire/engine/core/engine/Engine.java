package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.EngineVersion;
import com.millionnaire.engine.config.ConfigValidator;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.command.Command;
import com.millionnaire.engine.core.command.Input;
import com.millionnaire.engine.core.command.Tick;
import com.millionnaire.engine.core.event.Event;
import com.millionnaire.engine.core.event.EventLog;
import com.millionnaire.engine.core.event.KernelEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.DomainState;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.random.RandomSourceFactory;
import com.millionnaire.engine.random.XoshiroLemireV1;
import com.millionnaire.engine.serialize.Canonical;
import com.millionnaire.engine.serialize.Codec;
import com.millionnaire.engine.serialize.Envelope;
import com.millionnaire.engine.serialize.TypeRegistry;
import com.millionnaire.engine.time.ScheduledTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 引擎入口：decide → events → evolve。处理一个输入的顺序：
 * <ol>
 *   <li>按 {@link Input} 的契约检查序号（重复 / 过期 / 跳号 / 同号异文）；</li>
 *   <li>输入时间低于接收水位：拒绝（TIME_REGRESSION）；</li>
 *   <li>按任务总排序推进所有 dueAt ≤ serverTime 的任务（到期先于输入）；</li>
 *   <li>在工作区内先发出 InputAccepted，再交给领域判定；拒绝则丢弃工作区，只记录 InputRejected。</li>
 * </ol>
 * 引擎无状态、线程安全；外层负责同一房间输入串行化，以及把 requestId、请求摘要、结果与状态同事务持久化。
 */
public final class Engine<S extends DomainState> {
    /** 快照与事件日志的信封类型。 */
    public static final String SNAPSHOT_KIND = "snapshot";
    public static final String EVENT_LOG_KIND = "event-log";

    private final RuleConfig config;
    private final String configHash;
    private final Domain<S> domain;
    private final RandomSourceFactory random;
    private final Evolver<S> evolver;
    private final Codec codec;

    public Engine(RuleConfig config, Domain<S> domain) {
        this(config, domain, XoshiroLemireV1.INSTANCE);
    }

    /** random 可注入（测试用脚本随机源）；协议 ID 写入创世事件，续跑时必须一致。 */
    public Engine(RuleConfig config, Domain<S> domain, RandomSourceFactory random) {
        this.config = ConfigValidator.validateOrThrow(config);
        this.configHash = config.contentHash();
        this.domain = domain;
        this.random = random;
        this.evolver = new Evolver<>(domain);
        this.codec = new Codec(TypeRegistry.builder()
                .add(Command.class, Tick.class)
                .add(Event.class, KernelEvent.class)
                .build()
                .merge(domain.types()));
    }

    public RuleConfig config() {
        return config;
    }

    public String configHash() {
        return configHash;
    }

    public Domain<S> domain() {
        return domain;
    }

    /** 引擎专用编解码器（已登记内核与领域的全部多态类型）。 */
    public Codec codec() {
        return codec;
    }

    /** 创世：种子由外层（SecureRandom）生成并只保存在服务端。 */
    public StepResult create(String roomId, long seed, long at) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId missing");
        }
        Event genesis = new KernelEvent.Genesis(roomId, configHash, EngineVersion.VALUE, domain.id(),
                random.protocolId(), random.seed(seed), at, domain.initialState(config));
        return new StepResult(evolver.evolve(null, genesis), List.of(genesis), StepResult.Outcome.ACCEPTED, null);
    }

    /**
     * 外层在分配接收序号之前调用：命令无法规范编码（孤立代理字符、未登记的类型等）时抛 {@link InvalidInputException}。
     */
    public void admit(Command command) {
        digest(new Input(1, 0, command));
    }

    /**
     * 处理一个输入。异常契约：
     * <ul>
     *   <li>{@link StateValidationException}：传入的状态不合法（入口校验），未处理；</li>
     *   <li>{@link InvalidInputException}：输入无法规范编码，未消耗序号；</li>
     *   <li>{@link InputOrderException}：违反输入契约（跳号、同号异文），未处理；</li>
     *   <li>{@link KernelFaultException}：领域或内核程序错误（随机纪律、演化一致性、提交前边界校验），整步不提交、不得自动重试；</li>
     *   <li>正常规则拒绝不抛异常，返回 REJECTED。</li>
     * </ul>
     */
    public StepResult step(EngineState state, Input input) {
        validate(state);
        String digest = digest(input);
        if (input.seq() < state.lastSeq()) {
            return new StepResult(state, List.of(), StepResult.Outcome.STALE, null);
        }
        if (input.seq() == state.lastSeq()) {
            if (!digest.equals(state.lastInputDigest())) {
                throw new InputOrderException("seq " + input.seq() + " was already processed with different content");
            }
            return new StepResult(state, List.of(), StepResult.Outcome.DUPLICATE, null);
        }
        if (input.seq() != state.lastSeq() + 1) {
            throw new InputOrderException("seq gap: expected " + (state.lastSeq() + 1) + " but got " + input.seq());
        }
        try {
            StepResult result = process(state, input, digest);
            validate(result.state());
            return result;
        } catch (KernelFaultException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KernelFaultException("step seq " + input.seq() + " failed: " + e.getMessage(), e);
        }
    }

    private StepResult process(EngineState state, Input input, String digest) {
        long at = input.serverTime();
        if (at < state.lastReceivedAt()) {
            return reject(state, List.of(), input, digest, RejectionCode.TIME_REGRESSION);
        }
        List<Event> out = new ArrayList<>();
        EngineState s = state;
        Optional<ScheduledTask> due;
        while ((due = s.timers().firstDue(at)).isPresent()) {
            DecisionContext<S> ctx = context(s);
            ctx.emitKernel(new KernelEvent.TaskFired(due.get().taskId(), due.get().dueAt()));
            domain.onTask(ctx, due.get());
            s = finish(ctx, out);
        }
        DecisionContext<S> ctx = context(s);
        ctx.emitKernel(new KernelEvent.InputAccepted(input.seq(), at, digest));
        RejectionCode code = input.command() instanceof Tick ? null : domain.decide(ctx, input.command());
        if (code != null) {
            if (ctx.drewRandomness()) {
                throw new KernelFaultException("domain rejected " + input.command().getClass().getSimpleName()
                        + " with " + code + " after drawing randomness; validate before drawing");
            }
            return reject(s, out, input, digest, code);
        }
        s = finish(ctx, out);
        return new StepResult(s, out, StepResult.Outcome.ACCEPTED, null);
    }

    /** 外层下一次需要唤醒引擎的时刻（最早任务的 dueAt）。 */
    public OptionalLong nextWakeUp(EngineState state) {
        return state.timers().peek().map(t -> OptionalLong.of(t.dueAt())).orElse(OptionalLong.empty());
    }

    /** 输入的规范摘要（区分同序号的重复投递与不同内容）；无法规范编码时抛 {@link InvalidInputException}。 */
    public String digest(Input input) {
        try {
            return Canonical.sha256Hex(codec.bytes(input));
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("input cannot be canonically encoded: " + e.getMessage(), e);
        }
    }

    /** 状态哈希（规范字节的 SHA-256，结构哈希）。 */
    public String stateHash(EngineState state) {
        return Canonical.sha256Hex(codec.bytes(state));
    }

    // ------------------------------------------------------------ 持久化与恢复

    public String snapshot(EngineState state) {
        return Envelope.wrap(SNAPSHOT_KIND, codec.encode(state));
    }

    /** 恢复快照：先按信封版本选择解码器，再完整校验状态（版本绑定、内核一致性、领域一致性）。 */
    public EngineState restore(String text) {
        Envelope env = Envelope.parse(text, SNAPSHOT_KIND);
        EngineState s = switch (env.formatVersion()) {
            case 1 -> codec.decode(env.body(), EngineState.class);
            default -> throw new Envelope.UnsupportedFormatException("unsupported formatVersion " + env.formatVersion());
        };
        validate(s);
        return s;
    }

    public String encodeEvents(List<Event> events) {
        return Envelope.wrap(EVENT_LOG_KIND, codec.encode(new EventLog(events)));
    }

    public List<Event> decodeEvents(String text) {
        Envelope env = Envelope.parse(text, EVENT_LOG_KIND);
        return switch (env.formatVersion()) {
            case 1 -> codec.decode(env.body(), EventLog.class).events();
            default -> throw new Envelope.UnsupportedFormatException("unsupported formatVersion " + env.formatVersion());
        };
    }

    /** 仅凭事件日志重建状态：创世事件必须与本引擎的版本、配置、领域、随机协议一致；结果再做完整校验。 */
    public EngineState rebuild(List<Event> events) {
        if (events.isEmpty() || !(events.get(0) instanceof KernelEvent.Genesis g)) {
            throw new StateValidationException("event log must start with Genesis");
        }
        requireCompatible(g.engineVersion(), g.configHash(), g.domainId(), g.rngProtocol());
        EngineState s = evolver.evolveAll(null, events);
        validate(s);
        return s;
    }

    /**
     * 状态一致性校验：恢复与重建入口、每步输入入口、以及每步提交前（边界）都会执行。
     * 只含内核与领域的轻量检查（≤ 8 人）；账本全量重放只在恢复/审计入口执行。
     */
    public void validate(EngineState s) {
        requireCompatible(s);
        expect(s.roomId() != null && !s.roomId().isBlank(), "roomId missing");
        // 每个已处理输入恰好产生一条 InputAccepted/InputRejected，另有创世事件，故 eventCount > lastSeq
        expect(s.lastSeq() >= 0 && s.lastSeq() < s.eventCount() && s.eventCount() < Long.MAX_VALUE,
                "input cursor inconsistent with event count");
        expect((s.lastSeq() == 0) == (s.lastInputDigest() == null), "last input digest inconsistent with seq");
        expect(s.lastInputDigest() == null || s.lastInputDigest().matches("[0-9a-f]{64}"), "malformed input digest");
        expect(s.now() <= s.lastReceivedAt(), "business time ahead of receive watermark");
        expect(s.timers() != null && s.rng() != null && s.domain() != null, "kernel fields missing");
        expect(s.nextTaskId() >= 1 && s.nextTaskId() < Long.MAX_VALUE, "nextTaskId out of range");
        for (ScheduledTask t : s.timers().tasks()) {
            expect(t.taskId() < s.nextTaskId(), "task " + t.taskId() + " not yet allocated");
            expect(t.dueAt() > s.lastReceivedAt(), "task " + t.taskId() + " should already have fired");
        }
        expect(s.pendingDraws().isEmpty(), "unconsumed random draws at step boundary");
        expect(domain.stateType().isInstance(s.domain()), "domain state of wrong type");
        domain.validate(s, domain.stateType().cast(s.domain()), config);
    }

    // ------------------------------------------------------------ internals

    private DecisionContext<S> context(EngineState s) {
        return new DecisionContext<>(s, evolver, domain.stateType(), random, config);
    }

    private static EngineState finish(DecisionContext<?> ctx, List<Event> out) {
        if (!ctx.engineState().pendingDraws().isEmpty()) {
            throw new KernelFaultException("domain drew randomness without consuming it: " + ctx.engineState().pendingDraws());
        }
        out.addAll(ctx.events());
        return ctx.engineState();
    }

    private StepResult reject(EngineState s, List<Event> out, Input input, String digest, RejectionCode code) {
        Event rejected = new KernelEvent.InputRejected(input.seq(), input.serverTime(), digest, code,
                input.command().actor());
        List<Event> all = new ArrayList<>(out);
        all.add(rejected);
        return new StepResult(evolver.evolve(s, rejected), all, StepResult.Outcome.REJECTED, code);
    }

    private void requireCompatible(EngineState s) {
        requireCompatible(s.engineVersion(), s.configHash(), s.domainId(), s.rngProtocol());
    }

    /** 旧局不读新配置：引擎版本、配置哈希、领域与随机协议必须完全一致。 */
    private void requireCompatible(String engineVersion, String hash, String domainId, String rngProtocol) {
        if (!EngineVersion.VALUE.equals(engineVersion) || !configHash.equals(hash)
                || !domain.id().equals(domainId) || !random.protocolId().equals(rngProtocol)) {
            throw new StateValidationException("incompatible state: " + engineVersion + "/" + hash + "/" + domainId + "/"
                    + rngProtocol + ", engine has " + EngineVersion.VALUE + "/" + configHash + "/" + domain.id() + "/"
                    + random.protocolId());
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new StateValidationException(message);
        }
    }
}
