package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.*;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.*;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.random.DrawPoint;
import java.util.ArrayList;
import java.util.List;

/** Event-square effects. Every result is bound to one cursor; every random field is recomputed from audited draws. */
final class EventModule {
    private EventModule() { }
    static int totalWeight(RuleConfig c) {
        int total = 0;
        for (EventKind kind : EventKind.values()) { total = Math.addExact(total, c.eventWeights().get(kind)); }
        return total;
    }
    static EventKind pick(RuleConfig c, int value) {
        if (value < 0 || value >= totalWeight(c)) { throw new IllegalStateException("event draw out of range"); }
        int cumulative = 0;
        for (EventKind kind : EventKind.values()) {
            cumulative = Math.addExact(cumulative, c.eventWeights().get(kind));
            if (value < cumulative) { return kind; }
        }
        throw new IllegalStateException("event draw out of range");
    }
    static int cashBound(RuleConfig c) {
        return Math.toIntExact((c.economy().eventCashMax() - c.economy().eventCashMin()) / c.economy().eventCashStep() + 1);
    }
    static long cash(RuleConfig c, int value) {
        return Math.addExact(c.economy().eventCashMin(), Math.multiplyExact((long) value, c.economy().eventCashStep()));
    }
    static LandingResult drawnResult(EventKind kind) {
        return switch (kind) {
            case CASH_REWARD -> LandingResult.DRAW_REWARD;
            case CASH_FINE -> LandingResult.DRAW_FINE;
            case CARD -> LandingResult.DRAW_CARD;
            case MOVE -> LandingResult.DRAW_MOVE;
            case JAIL -> LandingResult.DRAW_JAIL;
            case BUILD -> LandingResult.DRAW_BUILD;
            case DOWNGRADE -> LandingResult.DRAW_DOWNGRADE;
            case TO_STATION -> LandingResult.DRAW_STATION;
            case TO_START -> LandingResult.DRAW_START;
        };
    }
    static LandingStep effect(EventKind kind) {
        return switch (kind) {
            case CASH_REWARD -> LandingStep.REWARD;
            case CASH_FINE -> LandingStep.FINE;
            case CARD -> LandingStep.CARD;
            case MOVE, TO_STATION, TO_START -> LandingStep.MOVE;
            case JAIL -> LandingStep.TO_JAIL;
            case BUILD -> LandingStep.EVENT_BUILD;
            case DOWNGRADE -> LandingStep.EVENT_DOWNGRADE;
        };
    }

    /** 抽取来源：决定时用 ctx::draw，重建时用 draws::take（同一协议顺序）。 */
    @FunctionalInterface
    interface Drawer { int draw(DrawPoint point, int bound); }

    /** 事件的位移参数。 */
    record Motion(MoveKind kind, int distance) {
        static final Motion NONE = new Motion(null, 0);
    }

    /** MOVE 抽方向与距离；去车站抽目标车站（前进到它）；回起点前进到 0 号格；其他没有位移。 */
    static Motion motion(RuleConfig c, BoardTemplate board, EventKind kind, int tile, Drawer drawer) {
        return switch (kind) {
            case MOVE -> {
                MoveKind move = drawer.draw(DrawPoint.EVENT_MOVE_DIRECTION, 2) == 0 ? MoveKind.EVENT_FORWARD : MoveKind.EVENT_BACKWARD;
                int bound = c.economy().eventMoveMaxSteps() - c.economy().eventMoveMinSteps() + 1;
                yield new Motion(move, c.economy().eventMoveMinSteps() + drawer.draw(DrawPoint.EVENT_MOVE_DISTANCE, bound));
            }
            case TO_STATION -> {
                List<Integer> stations = board.tiles().stream().filter(t -> t.type() == TileType.STATION).map(Tile::index).toList();
                int target = stations.get(drawer.draw(DrawPoint.EVENT_STATION, stations.size()));
                yield new Motion(MoveKind.EVENT_FORWARD, Math.floorMod(target - tile, board.size()));
            }
            case TO_START -> new Motion(MoveKind.EVENT_FORWARD, Math.floorMod(-tile, board.size()));
            default -> Motion.NONE;
        };
    }

    /** 幸运 / 不幸格的位移：不幸奖池的 MOVE 固定后退、格数按事件位移区间抽（不抽方向）；其余同抽卡事件。 */
    static Motion fixedMotion(RuleConfig c, BoardTemplate board, FixedEvent f, int tile, Drawer drawer) {
        if (f.kind() == EventKind.MOVE) {
            int bound = c.economy().eventMoveMaxSteps() - c.economy().eventMoveMinSteps() + 1;
            return new Motion(MoveKind.EVENT_BACKWARD, c.economy().eventMoveMinSteps() + drawer.draw(DrawPoint.EVENT_MOVE_DISTANCE, bound));
        }
        return motion(c, board, f.kind(), tile, drawer);
    }

    /** 加盖 / 降级的候选：自己的未抵押、未锁定普通地产（加盖要未满级，降级要有等级），按格子顺序。 */
    static List<Integer> propertyTargets(RuleConfig c, BoardTemplate board, GameState g, String player, boolean build) {
        List<Integer> out = new ArrayList<>();
        for (Tile t : board.tiles()) {
            if (t.type() != TileType.PROPERTY) { continue; }
            var o = g.board().ownable(t.index()).orElse(null);
            if (o == null || !player.equals(o.owner()) || o.mortgaged() || o.lockedBy() != null) { continue; }
            if (build ? o.level() < c.economy().maxLevel() : o.level() > 0) { out.add(t.index()); }
        }
        return out;
    }
    static RejectionCode decide(DecisionContext<SessionState> ctx, GameCommand c) {
        long window = c instanceof GameCommand.DrawEventCard d ? d.windowId()
                : c instanceof GameCommand.PickStartCard p ? p.windowId() : ((GameCommand.DiscardCard) c).windowId();
        RejectionCode why = TurnModule.checkTurnWindow(ctx, c.actor(), window);
        if (why != null) { return why; }
        if (!StageTable.rule(StageTable.turnPoint(ctx.state().game())).allows(c)) { return RejectionCode.WRONG_STAGE; }
        if (c instanceof GameCommand.PickStartCard p) {
            if (p.index() < 0 || p.index() >= StartPick.CHOICES) { return RejectionCode.INVALID_ARGUMENT; }
            TurnModule.closeDecision(ctx);
            pickStart(ctx, p.index(), false);
            return null;
        }
        if (c instanceof GameCommand.DiscardCard d) {
            var hand = ctx.state().game().player(c.actor()).orElseThrow().hand();
            if (d.index() < 0 || d.index() >= hand.size()) { return RejectionCode.INVALID_ARGUMENT; }
            TurnModule.closeDecision(ctx);
            discard(ctx, d.index(), false);
        } else {
            TurnModule.closeDecision(ctx);
            draw(ctx, false);
        }
        return null;
    }
    static void draw(DecisionContext<SessionState> ctx, boolean auto) {
        var c = ctx.config();
        EventKind kind = pick(c, ctx.draw(DrawPoint.EVENT_KIND, totalWeight(c)));
        var g = ctx.state().game();
        var l = g.turn().landing();
        long amount = 0;
        Motion m = Motion.NONE;
        // Protocol order: kind, then cash OR motion draws; EVENT_CARD / EVENT_TARGET are consumed by the effect.
        if (kind == EventKind.CASH_REWARD || kind == EventKind.CASH_FINE) {
            amount = cash(c, ctx.draw(DrawPoint.EVENT_CASH, cashBound(c)));
        } else {
            m = motion(c, LobbyModule.board(c, g.settings()), kind, l.tile(), ctx::draw);
        }
        ctx.emit(new GameEvent.EventDrawn(g.turn().currentPlayer(), l.landingId(), l.cursor(), kind, amount, m.kind(), m.distance(), auto));
        EconomyModule.resumeLanding(ctx, 0);
    }
    /** 起点三选一：按配置比例抽现金或道具（现金再抽金额）；道具由后续的 CARD 效果按事件得道具的概率抽。 */
    static void pickStart(DecisionContext<SessionState> ctx, int index, boolean auto) {
        var sp = ctx.config().startPick();
        var g = ctx.state().game();
        var l = g.turn().landing();
        boolean cash = ctx.draw(DrawPoint.START_PICK_KIND, sp.cashWeight() + sp.cardWeight()) < sp.cashWeight();
        long amount = cash ? sp.cashMin() + (long) ctx.draw(DrawPoint.START_PICK_CASH, sp.cashBound()) * sp.cashStep() : 0;
        ctx.emit(new GameEvent.StartPickDrawn(g.turn().currentPlayer(), l.landingId(), l.cursor(), index,
                cash ? EventKind.CASH_REWARD : EventKind.CARD, amount, auto));
        EconomyModule.resumeLanding(ctx, 0);
    }

    static void performEffect(DecisionContext<SessionState> ctx) {
        var g = ctx.state().game();
        var l = g.turn().landing();
        String player = g.turn().currentPlayer();
        switch (l.currentTask()) {
            case FIXED_EVENT -> {
                var board = LobbyModule.board(ctx.config(), g.settings());
                var pool = board.pool(board.tiles().get(l.tile()).type());
                int pick = BoardTemplate.pick(pool, ctx.draw(DrawPoint.LUCKY_EVENT, BoardTemplate.weight(pool)));
                var f = pool.get(pick);
                Motion m = fixedMotion(ctx.config(), board, f, l.tile(), ctx::draw);
                ctx.emit(new GameEvent.FixedEventTriggered(player, l.landingId(), l.cursor(), f.kind(), f.amount(), m.kind(), m.distance(), pick));
                EconomyModule.resumeLanding(ctx, 0);
            }
            case EVENT_BUILD, EVENT_DOWNGRADE -> {
                boolean build = l.currentTask() == LandingStep.EVENT_BUILD;
                var targets = propertyTargets(ctx.config(), LobbyModule.board(ctx.config(), g.settings()), g, player, build);
                if (targets.isEmpty()) {
                    ctx.emit(new GameEvent.EventPropertyChanged(player, l.landingId(), l.cursor(), -1, -1));
                } else {
                    int tile = targets.get(ctx.draw(DrawPoint.EVENT_TARGET, targets.size()));
                    int level = g.board().ownable(tile).orElseThrow().level() + (build ? 1 : -1);
                    ctx.emit(new GameEvent.EventPropertyChanged(player, l.landingId(), l.cursor(), tile, level));
                }
                EconomyModule.resumeLanding(ctx, 0);
            }
            case REWARD -> {
                ctx.emit(new GameEvent.EventRewardPaid(player, l.landingId(), l.cursor(), l.event().amount()));
                EconomyModule.resumeLanding(ctx, 0);
            }
            case FINE -> EconomyModule.chargeFee(ctx,
                    new FeeSource(FeeSource.Kind.FINE, l.landingId(), l.cursor(), l.tile(), null, l.event().amount()), 0);
            case CARD -> {
                var card = CardDeck.pick(ctx.config(), ctx.draw(DrawPoint.EVENT_CARD, CardDeck.totalWeight(ctx.config())));
                ctx.emit(new GameEvent.EventCardReceived(player, l.landingId(), l.cursor(), card));
                ctx.emit(new GameEvent.EventHandCount(player, l.landingId(), l.cursor(), ctx.state().game().player(player).orElseThrow().hand().size(), false));
                EconomyModule.resumeLanding(ctx, 0);
            }
            case MOVE, TO_JAIL -> {
                MoveKind kind = l.currentTask() == LandingStep.TO_JAIL ? MoveKind.TO_JAIL : l.event().moveKind();
                int distance = kind == MoveKind.TO_JAIL ? 0 : l.event().distance();
                ctx.emit(new GameEvent.EventMoveCommitted(player, l.landingId(), l.cursor(), kind, distance));
                EconomyModule.completeLanding(ctx);
                var chain = ctx.state().game().turn().chain();
                var board = LobbyModule.board(ctx.config(), g.settings());
                var segment = kind == MoveKind.TO_JAIL
                        ? MovementRules.jailJump(chain.segments().size() + 1, l.tile(), TurnModule.jailIndex(board), board.size())
                        : MovementRules.segment(chain.segments().size() + 1, kind, l.tile(), distance, board.size());
                TurnModule.moveAndLand(ctx, segment, 0);
            }
            default -> throw new IllegalStateException("not an event effect " + l.currentTask());
        }
    }
    static void discard(DecisionContext<SessionState> ctx, int index, boolean auto) {
        var g = ctx.state().game();
        var l = g.turn().landing();
        String player = g.turn().currentPlayer();
        var card = g.player(player).orElseThrow().hand().get(index);
        ctx.emit(new GameEvent.EventCardDiscarded(player, l.landingId(), l.cursor(), index, card, auto));
        ctx.emit(new GameEvent.EventHandCount(player, l.landingId(), l.cursor(), ctx.state().game().player(player).orElseThrow().hand().size(), true));
        EconomyModule.resumeLanding(ctx, 0);
    }
    static boolean handles(GameEvent e) {
        return e instanceof GameEvent.EventDrawn || e instanceof GameEvent.FixedEventTriggered || e instanceof GameEvent.StartPickDrawn
                || e instanceof GameEvent.EventPropertyChanged || e instanceof GameEvent.EventRewardPaid
                || e instanceof GameEvent.FeeCharged || e instanceof GameEvent.FeePaid || e instanceof GameEvent.EventMoveCommitted
                || e instanceof GameEvent.EventCardReceived || e instanceof GameEvent.EventCardDiscarded || e instanceof GameEvent.EventHandCount;
    }
    static boolean source(GameState g, String player, long id, int cursor, LandingStep task) {
        var l = g.turn().landing();
        return l != null && player != null && player.equals(g.turn().currentPlayer()) && l.landingId() == id
                && l.cursor() == cursor && l.currentTask() == task
                && g.flow().frames().isEmpty() && g.debt() == null;
    }
    static GameState evolve(GameState g, GameEvent event, Draws draws, RuleConfig c) {
        var t = g.turn();
        var l = t.landing();
        var k = t.track();
        return switch (event) {
            case GameEvent.EventDrawn e -> {
                LobbyModule.check(source(g, e.playerId(), e.landingId(), e.cursor(), LandingStep.EVENT)
                        && l.step() == LandingStep.EVENT && l.decisionOpen() && l.event() == null
                        && t.chain() != null && !t.chain().eventDrawn(), "event draw source mismatch");
                EventKind kind = pick(c, draws.take(DrawPoint.EVENT_KIND, totalWeight(c)));
                LobbyModule.check(e.kind() == kind, "event kind does not match draw");
                long amount = 0;
                Motion m = Motion.NONE;
                if (kind == EventKind.CASH_REWARD || kind == EventKind.CASH_FINE) {
                    amount = cash(c, draws.take(DrawPoint.EVENT_CASH, cashBound(c)));
                } else {
                    m = motion(c, LobbyModule.board(c, g.settings()), kind, l.tile(), draws::take);
                }
                LobbyModule.check(e.amount() == amount, "event amount does not match draw");
                LobbyModule.check(e.moveKind() == m.kind(), "event direction does not match draw");
                LobbyModule.check(e.distance() == m.distance(), "event distance does not match draw");
                var result = new EventResolution(kind, amount, m.kind(), m.distance(), -1, false);
                var after = g.withTurn(t.withChain(t.chain().drewEvent()));
                yield after.withTurn(after.turn().withLanding(LandingRules.consume(c, after, l.withEvent(result), drawnResult(kind))));
            }
            case GameEvent.StartPickDrawn e -> {
                LobbyModule.check(source(g, e.playerId(), e.landingId(), e.cursor(), LandingStep.START_PICK)
                        && l.step() == LandingStep.START_PICK && l.decisionOpen() && l.event() == null
                        && e.index() >= 0 && e.index() < StartPick.CHOICES, "start pick source mismatch");
                var sp = c.startPick();
                boolean cash = draws.take(DrawPoint.START_PICK_KIND, sp.cashWeight() + sp.cardWeight()) < sp.cashWeight();
                long amount = cash ? sp.cashMin() + (long) draws.take(DrawPoint.START_PICK_CASH, sp.cashBound()) * sp.cashStep() : 0;
                EventKind kind = cash ? EventKind.CASH_REWARD : EventKind.CARD;
                LobbyModule.check(e.kind() == kind && e.amount() == amount, "start pick does not match draw");
                var result = new EventResolution(kind, amount, null, 0, -1, false);
                yield g.withTurn(t.withLanding(LandingRules.consume(c, g, l.withEvent(result), drawnResult(kind))));
            }
            case GameEvent.FixedEventTriggered e -> {
                LobbyModule.check(source(g, e.playerId(), e.landingId(), e.cursor(), LandingStep.FIXED_EVENT)
                        && l.event() == null && t.chain() != null && !t.chain().eventDrawn(), "fixed event source mismatch");
                var board = LobbyModule.board(c, g.settings());
                var pool = board.pool(board.tiles().get(l.tile()).type());
                int pick = BoardTemplate.pick(pool, draws.take(DrawPoint.LUCKY_EVENT, BoardTemplate.weight(pool)));
                var f = pool.get(pick);
                LobbyModule.check(e.pick() == pick && e.kind() == f.kind() && e.amount() == f.amount(), "lucky event does not match draw");
                Motion m = fixedMotion(c, board, f, l.tile(), draws::take);
                LobbyModule.check(e.moveKind() == m.kind() && e.distance() == m.distance(), "fixed event motion does not match draw");
                var result = new EventResolution(f.kind(), f.amount(), m.kind(), m.distance(), -1, false);
                var after = g.withTurn(t.withChain(t.chain().drewEvent()));
                yield after.withTurn(after.turn().withLanding(LandingRules.consume(c, after, l.withEvent(result), drawnResult(f.kind()))));
            }
            case GameEvent.EventPropertyChanged e -> {
                boolean build = l != null && l.currentTask() == LandingStep.EVENT_BUILD;
                LandingStep task = build ? LandingStep.EVENT_BUILD : LandingStep.EVENT_DOWNGRADE;
                LobbyModule.check(source(g, e.playerId(), e.landingId(), e.cursor(), task) && l.event() != null
                        && l.event().kind() == (build ? EventKind.BUILD : EventKind.DOWNGRADE), "event property change source mismatch");
                var targets = propertyTargets(c, LobbyModule.board(c, g.settings()), g, e.playerId(), build);
                GameState changed = g;
                if (targets.isEmpty()) {
                    LobbyModule.check(e.tile() == -1 && e.level() == -1, "event property change without a target");
                } else {
                    int tile = targets.get(draws.take(DrawPoint.EVENT_TARGET, targets.size()));
                    var o = g.board().ownable(tile).orElseThrow();
                    LobbyModule.check(e.tile() == tile && e.level() == o.level() + (build ? 1 : -1), "event property change does not match draw");
                    changed = g.withBoard(g.board().with(o.level(e.level())));
                }
                yield changed.withTurn(t.withLanding(LandingRules.consume(c, changed, l, build ? LandingResult.BUILT : LandingResult.DOWNGRADED)));
            }
            case GameEvent.EventRewardPaid e -> {
                LobbyModule.check(source(g, e.playerId(), e.landingId(), e.cursor(), LandingStep.REWARD)
                        && l.event() != null && l.event().kind() == EventKind.CASH_REWARD && e.amount() == l.event().amount(), "event reward mismatch");
                var paid = g.withLedger(g.ledger().transfer(Ledger.SYSTEM, e.playerId(), e.amount(), "EVENT_REWARD", "landing-" + l.landingId()));
                yield paid.withTurn(t.withLanding(LandingRules.consume(c, paid, l, LandingResult.REWARDED)));
            }
            case GameEvent.FeeCharged e -> {
                LobbyModule.check(e.payer().equals(t.currentPlayer()) && k.pendingCharge() == 0 && g.debt() == null
                        && l != null && l.next() == LandingStep.FINE && FeeRules.valid(c, g, l, e.source()), "system fee charge mismatch");
                yield g.withTurn(t.withLanding(l.withStep(null, e.source().amount()))
                        .withTrack(k.fee(e.source(), EconomyModule.debtPath(c, g, e.payer(), e.source().amount()))));
            }
            case GameEvent.FeePaid e -> {
                LobbyModule.check(e.payer().equals(t.currentPlayer()) && g.debt() == null && e.source() != null
                        && e.source().equals(k.feeSource()) && k.pendingCharge() == e.source().amount(), "system fee payment mismatch");
                var paid = g.withLedger(g.ledger().transfer(e.payer(), FeeRules.account(e.source()), e.source().amount(),
                        FeeRules.rule(e.source().kind()).cause(), "landing-" + l.landingId()));
                yield paid.withTurn(t.withLanding(LandingRules.consume(c, paid, l, LandingResult.PAID)).withTrack(k.charge(0)));
            }
            case GameEvent.EventMoveCommitted e -> {
                LandingStep task = e.kind() == MoveKind.TO_JAIL ? LandingStep.TO_JAIL : LandingStep.MOVE;
                LobbyModule.check(source(g, e.playerId(), e.landingId(), e.cursor(), task) && l.event() != null
                        && (task == LandingStep.TO_JAIL ? l.event().kind() == EventKind.JAIL && e.distance() == 0
                            : (l.event().kind() == EventKind.MOVE || l.event().kind() == EventKind.TO_STATION || l.event().kind() == EventKind.TO_START)
                                && e.kind() == l.event().moveKind() && e.distance() == l.event().distance())
                        && k.eventMove() == null, "event relocation source mismatch");
                var credential = new EventMove(e.landingId(), e.cursor(), e.playerId(), e.kind(), e.distance());
                yield g.withTurn(t.withLanding(LandingRules.consume(c, g, l, LandingResult.MOVED)).withTrack(k.redirect(credential))
                        .withChain(t.chain().authorize(new MovePlan(e.kind(), e.distance(), e.landingId(), e.cursor()))));
            }
            case GameEvent.EventCardReceived e -> {
                LobbyModule.check(source(g, e.recipient(), e.landingId(), e.cursor(), LandingStep.CARD)
                        && l.event() != null && l.event().kind() == EventKind.CARD && l.event().newCardIndex() == -1, "event card source mismatch");
                int cardDraw = draws.take(DrawPoint.EVENT_CARD, CardDeck.totalWeight(c));
                CardType card = CardDeck.pick(c, cardDraw);
                LobbyModule.check(e.card() == card, "event card does not match draw");
                var p = g.player(e.recipient()).orElseThrow();
                LobbyModule.check(p.hand().size() <= c.economy().handLimit(), "cannot receive a second overflow card");
                var hand = new ArrayList<>(p.hand());
                hand.add(card);
                yield g.withPlayer(p.withHand(hand)).withTurn(t.withLanding(l.withEvent(l.event().received(p.hand().size(), cardDraw))));
            }
            case GameEvent.EventCardDiscarded e -> {
                LobbyModule.check(source(g, e.recipient(), e.landingId(), e.cursor(), LandingStep.DISCARD)
                        && l.step() == LandingStep.DISCARD && l.decisionOpen() && l.event() != null
                        && !l.event().countPending(), "event discard source mismatch");
                var p = g.player(e.recipient()).orElseThrow();
                LobbyModule.check(p.hand().size() == c.economy().handLimit() + 1 && e.index() >= 0 && e.index() < p.hand().size()
                        && p.hand().get(e.index()) == e.card(), "event discard index/card mismatch");
                LobbyModule.check(!e.auto() || e.index() == l.event().newCardIndex(), "automatic discard must abandon the new card");
                var hand = new ArrayList<>(p.hand());
                hand.remove(e.index());
                yield g.withPlayer(p.withHand(hand)).withTurn(t.withLanding(l.withEvent(l.event().discarded())));
            }
            case GameEvent.EventHandCount e -> {
                var task = e.discarded() ? LandingStep.DISCARD : LandingStep.CARD;
                LobbyModule.check(source(g, e.playerId(), e.landingId(), e.cursor(), task) && l.event() != null
                        && l.event().countPending() && e.handCount() == g.player(e.playerId()).orElseThrow().hand().size(), "event hand count mismatch");
                var updated = l.withEvent(l.event().countReported());
                yield g.withTurn(t.withLanding(LandingRules.consume(c, g, updated, e.discarded() ? LandingResult.DISCARDED : LandingResult.CARD_RECEIVED)));
            }
            default -> throw new IllegalStateException("not an event-square event");
        };
    }
    static boolean hasKind(LandingState l, EventKind kind) { return l.event() != null && l.event().kind() == kind; }
    static boolean validResolution(RuleConfig c, LandingState l) {
        var r = l.event();
        if (r == null) { return !l.results().stream().anyMatch(x -> x.name().startsWith("DRAW_")); }
        if (r.kind() == null || r.countPending() || !l.results().contains(drawnResult(r.kind()))) { return false; }
        if (l.tasks().getFirst() == LandingStep.START_PICK) {
            // 起点三选一：只有现金（配置区间内）或道具
            var sp = c.startPick();
            return r.moveKind() == null && r.distance() == 0 && (r.kind() == EventKind.CASH_REWARD
                    ? r.amount() >= sp.cashMin() && r.amount() <= sp.cashMax() && (r.amount() - sp.cashMin()) % sp.cashStep() == 0
                        && r.newCardIndex() == -1 && r.cardDraw() == -1
                    : r.kind() == EventKind.CARD && r.amount() == 0
                        && (r.newCardIndex() == -1 && r.cardDraw() == -1 || r.newCardIndex() == c.economy().handLimit()
                            && r.cardDraw() >= 0 && r.cardDraw() < CardDeck.totalWeight(c)));
        }
        if (l.tasks().getFirst() != LandingStep.EVENT && l.tasks().getFirst() != LandingStep.FIXED_EVENT) { return false; }
        boolean monetary = r.kind() == EventKind.CASH_REWARD || r.kind() == EventKind.CASH_FINE;
        boolean moving = r.kind() == EventKind.MOVE;
        boolean jumping = r.kind() == EventKind.TO_STATION || r.kind() == EventKind.TO_START;
        return (monetary ? r.amount() >= c.economy().eventCashMin() && r.amount() <= c.economy().eventCashMax()
                    && (r.amount() - c.economy().eventCashMin()) % c.economy().eventCashStep() == 0 : r.amount() == 0)
                && (moving ? (r.moveKind() == MoveKind.EVENT_FORWARD || r.moveKind() == MoveKind.EVENT_BACKWARD)
                    && r.distance() >= c.economy().eventMoveMinSteps() && r.distance() <= c.economy().eventMoveMaxSteps()
                    : jumping ? r.moveKind() == MoveKind.EVENT_FORWARD && r.distance() >= 1
                    : r.moveKind() == null && r.distance() == 0)
                && (r.kind() == EventKind.CARD ? r.newCardIndex() == c.economy().handLimit()
                        && r.cardDraw() >= 0 && r.cardDraw() < CardDeck.totalWeight(c) : r.newCardIndex() == -1 && r.cardDraw() == -1);
    }
}
