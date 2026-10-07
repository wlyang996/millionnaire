package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.LandingResult;
import com.millionnaire.engine.core.state.LandingState;
import com.millionnaire.engine.core.state.LandingStep;
import java.util.List;

/**
 * 任务、结果与后继的数据表，决定和重建读取同一表。后继资格在结果演化后的状态求值。
 * 增加响应/事件/建造卡时，增加表行、对应结果事件及执行器；未注册种类拒绝，不能默认跳过。
 */
final class LandingRules {
    private LandingRules() { }
    /** WINDOW：回合决策窗口；RENT：缴租；EFFECT：即时效果；FLOW：覆盖流程（回合进入 AWAITING_FLOW，如小游戏）。 */
    enum Execution { WINDOW, RENT, EFFECT, FLOW }
    @FunctionalInterface
    interface Gate { boolean allows(RuleConfig config, GameState game, LandingState landing); }
    static final Gate ALWAYS = (c, g, l) -> true;
    static final Gate CAN_UPGRADE = (c, g, l) -> g.board().ownable(l.tile()).map(o ->
            EconomyModule.canUpgrade(c, LobbyModule.board(c, g.settings()), g, g.turn().currentPlayer(), o)).orElse(false);
    record Successor(LandingStep step, Gate gate) { }
    record Outcome(LandingResult result, List<Successor> successors) { }
    record Rule(LandingStep step, Execution execution, StageTable.Point point, Gate prerequisite, Gate resting,
                List<Outcome> outcomes) {
        Outcome outcome(LandingResult result) {
            return outcomes.stream().filter(o -> o.result() == result).findFirst()
                    .orElseThrow(() -> new IllegalStateException("result does not consume current landing task"));
        }
    }
    static final List<Rule> RULES = List.of(
        new Rule(LandingStep.BUY, Execution.WINDOW, StageTable.Point.BUY, (c, g, l) ->
                g.board().ownable(l.tile()).map(o -> o.owner() == null && (c.economy().offerUnaffordablePurchase()
                        || g.ledger().available(g.turn().currentPlayer())
                        >= EconomyModule.basePrice(c, LobbyModule.board(c, g.settings()).tiles().get(l.tile())))).orElse(false),
                (c, g, l) -> g.board().ownable(l.tile()).map(o -> o.owner() == null
                        || o.owner().equals(g.turn().currentPlayer()) && l.bought()).orElse(false), List.of(
                new Outcome(LandingResult.BOUGHT, List.of(new Successor(LandingStep.UPGRADE,
                        (c, g, l) -> c.economy().upgradeAfterPurchase() && CAN_UPGRADE.allows(c, g, l)))),
                new Outcome(LandingResult.DECLINED, List.of()),
                new Outcome(LandingResult.AUCTIONED, List.of(new Successor(LandingStep.AUCTION, ALWAYS))))),
        new Rule(LandingStep.UPGRADE, Execution.WINDOW, StageTable.Point.UPGRADE, CAN_UPGRADE,
                (c, g, l) -> g.board().ownable(l.tile()).map(o -> g.turn().currentPlayer().equals(o.owner()) && !o.mortgaged()
                        && LobbyModule.board(c, g.settings()).tiles().get(l.tile()).type() == com.millionnaire.engine.config.TileType.PROPERTY).orElse(false), List.of(
                new Outcome(LandingResult.UPGRADED, List.of()), new Outcome(LandingResult.SKIPPED, List.of()))),
        new Rule(LandingStep.BANK, Execution.WINDOW, StageTable.Point.BANK, (c, g, l) ->
                LobbyModule.board(c, g.settings()).tiles().get(l.tile()).type() == com.millionnaire.engine.config.TileType.BANK
                        && g.phase() == com.millionnaire.engine.core.state.GamePhase.RUNNING,
                (c, g, l) -> LobbyModule.board(c, g.settings()).tiles().get(l.tile()).type() == com.millionnaire.engine.config.TileType.BANK,
                List.of(new Outcome(LandingResult.BANK_FINISHED, List.of()))),
        new Rule(LandingStep.RENT, Execution.RENT, null, ALWAYS, (c, g, l) -> false,
                List.of(new Outcome(LandingResult.PAID, List.of()))),
        new Rule(LandingStep.EVENT, Execution.WINDOW, StageTable.Point.EVENT_DRAW,
                (c, g, l) -> eventTile(c, g, l) && !g.turn().chain().eventDrawn(),
                (c, g, l) -> eventTile(c, g, l) && !g.turn().chain().eventDrawn() && l.event() == null,
                java.util.Arrays.stream(com.millionnaire.engine.config.EventKind.values()).map(kind ->
                    new Outcome(EventModule.drawnResult(kind), List.of(new Successor(EventModule.effect(kind),
                            (c, g, l) -> EventModule.hasKind(l, kind))))).toList()),
        new Rule(LandingStep.FIXED_EVENT, Execution.EFFECT, null,
                (c, g, l) -> fixedEventTile(c, g, l) && !g.turn().chain().eventDrawn(), (c, g, l) -> false,
                java.util.Arrays.stream(com.millionnaire.engine.config.EventKind.values()).map(kind ->
                    new Outcome(EventModule.drawnResult(kind), List.of(new Successor(EventModule.effect(kind),
                            (c, g, l) -> EventModule.hasKind(l, kind))))).toList()),
        new Rule(LandingStep.EVENT_BUILD, Execution.EFFECT, null, ALWAYS, (c, g, l) -> false,
                List.of(new Outcome(LandingResult.BUILT, List.of()))),
        new Rule(LandingStep.EVENT_DOWNGRADE, Execution.EFFECT, null, ALWAYS, (c, g, l) -> false,
                List.of(new Outcome(LandingResult.DOWNGRADED, List.of()))),
        new Rule(LandingStep.REWARD, Execution.EFFECT, null, ALWAYS, (c, g, l) -> false,
                List.of(new Outcome(LandingResult.REWARDED, List.of()))),
        new Rule(LandingStep.FINE, Execution.EFFECT, null, ALWAYS, (c, g, l) -> false,
                List.of(new Outcome(LandingResult.PAID, List.of()))),
        new Rule(LandingStep.CARD, Execution.EFFECT, null, ALWAYS, (c, g, l) -> false,
                List.of(new Outcome(LandingResult.CARD_RECEIVED, List.of(new Successor(LandingStep.DISCARD,
                        (c, g, l) -> g.player(g.turn().currentPlayer()).orElseThrow().hand().size() > c.economy().handLimit()))))),
        new Rule(LandingStep.DISCARD, Execution.WINDOW, StageTable.Point.DISCARD,
                (c, g, l) -> EventModule.hasKind(l, com.millionnaire.engine.config.EventKind.CARD)
                        && g.player(g.turn().currentPlayer()).orElseThrow().hand().size() == c.economy().handLimit() + 1,
                (c, g, l) -> EventModule.hasKind(l, com.millionnaire.engine.config.EventKind.CARD)
                        && g.player(g.turn().currentPlayer()).orElseThrow().hand().size() == c.economy().handLimit() + 1,
                List.of(new Outcome(LandingResult.DISCARDED, List.of()))),
        new Rule(LandingStep.MOVE, Execution.EFFECT, null, ALWAYS, (c, g, l) -> false,
                List.of(new Outcome(LandingResult.MOVED, List.of()))),
        new Rule(LandingStep.TO_JAIL, Execution.EFFECT, null, ALWAYS, (c, g, l) -> false,
                List.of(new Outcome(LandingResult.MOVED, List.of()))),
        new Rule(LandingStep.RESPONSE, Execution.WINDOW, StageTable.Point.RENT_RESPONSE,
                (c, g, l) -> CardModule.rentResponseDue(c, g, l.tile()), (c, g, l) -> CardModule.rentResponseDue(c, g, l.tile()),
                List.of(new Outcome(LandingResult.WAIVED, List.of()),
                        new Outcome(LandingResult.DECLINED, List.of(new Successor(LandingStep.RENT, ALWAYS))))),
        new Rule(LandingStep.AUCTION, Execution.FLOW, null, (c, g, l) -> AuctionModule.landAuctionLegal(c, g, l.tile()),
                (c, g, l) -> AuctionModule.resting(g, l), List.of(new Outcome(LandingResult.AUCTION_ENDED, List.of()))),
        new Rule(LandingStep.MINIGAME, Execution.FLOW, null, (c, g, l) -> MinigameModule.eligible(c, g, l.tile()),
                (c, g, l) -> MinigameModule.resting(g, l), List.of(new Outcome(LandingResult.PLAYED, List.of()))));

    static boolean fixedEventTile(RuleConfig c, GameState g, LandingState l) {
        return LobbyModule.board(c, g.settings()).tiles().get(l.tile()).type() == com.millionnaire.engine.config.TileType.FIXED_EVENT;
    }

    static boolean eventTile(RuleConfig c, GameState g, LandingState l) {
        return LobbyModule.board(c, g.settings()).tiles().get(l.tile()).type() == com.millionnaire.engine.config.TileType.EVENT;
    }

    static boolean canEnter(RuleConfig c, GameState g, LandingState l, LandingStep step, long payment) {
        if (l.decisionOpen()) { return false; }
        if (step == LandingStep.DEBT) {
            var d = g.debt();
            return l.step() == null && d != null && d.source() != null && l.currentTask() == FeeRules.rule(d.source().kind()).task()
                    && d.source().landingId() == l.landingId() && d.source().cursor() == l.cursor()
                    && d.path() == com.millionnaire.engine.core.state.DebtPath.MANUAL && d.amount() == payment && d.segment() == 0;
        }
        Rule r = rule(step);
        return (r.execution() == Execution.WINDOW || r.execution() == Execution.FLOW) && l.next() == step && payment == 0
                && r.prerequisite().allows(c, g, l);
    }
    static boolean resting(RuleConfig c, GameState g, LandingState l) {
        if (l.step() == LandingStep.DEBT) {
            var d = g.debt();
            return g.turn().stage() == com.millionnaire.engine.core.state.TurnStage.AWAITING_FLOW && d != null
                    && d.source().landingId() == l.landingId() && d.source().cursor() == l.cursor()
                    && d.source() != null && d.amount() == l.pendingPayment();
        }
        Rule r = rule(l.step());
        if (r.execution() == Execution.FLOW) { return r.resting().allows(c, g, l); }
        // Cash may change during a decision (redeem); entry affordability is not a resting invariant.
        return r.execution() == Execution.WINDOW && g.turn().stage() == com.millionnaire.engine.core.state.TurnStage.LANDING
                && r.resting().allows(c, g, l);
    }

    static Rule rule(LandingStep step) {
        return RULES.stream().filter(r -> r.step() == step).findFirst()
                .orElseThrow(() -> new IllegalStateException("unregistered landing task " + step));
    }
    static List<LandingStep> initial(RuleConfig config, GameState g, String player, int tile) {
        LandingStep first = EconomyModule.requiredStep(config, g, player, tile);
        return first == null ? List.of() : List.of(first);
    }
    static LandingState consume(RuleConfig config, GameState after, LandingState l, LandingResult result) {
        List<LandingStep> following = rule(l.currentTask()).outcome(result).successors().stream()
                .filter(s -> s.gate().allows(config, after, l))
                .map(Successor::step).toList();
        return l.consumed(result, following);
    }
    static boolean valid(LandingState l) {
        if (l.tasks() == null || l.generatedBy() == null || l.results() == null || l.cursor() < 0
                || l.cursor() > l.tasks().size() || l.results().size() != l.cursor()
                || l.generatedBy().size() != l.tasks().size()) { return false; }
        int priorParent = -1;
        int priorSuccessor = -1;
        for (int i = 0; i < l.tasks().size(); i++) {
            LandingStep step = l.tasks().get(i);
            if (RULES.stream().noneMatch(r -> r.step() == step)) { return false; }
            if (i < l.cursor()) { rule(step).outcome(l.results().get(i)); }
            int parent = l.generatedBy().get(i);
            if (parent < -1 || parent >= i || parent >= l.cursor() || parent < priorParent) { return false; }
            if (parent == -1) {
                if (i != 0) { return false; } // M2 每个落点至多一个初始任务
            } else {
                var candidates = rule(l.tasks().get(parent)).outcome(l.results().get(parent)).successors();
                int begin = parent == priorParent ? priorSuccessor + 1 : 0;
                int found = -1;
                for (int j = begin; j < candidates.size(); j++) {
                    if (candidates.get(j).step() == step) { found = j; break; }
                }
                if (found == -1) { return false; }
                priorSuccessor = found;
            }
            priorParent = parent;
        }
        return l.bought() == l.results().contains(LandingResult.BOUGHT);
    }
}
