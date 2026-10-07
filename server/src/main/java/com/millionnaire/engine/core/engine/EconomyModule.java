package com.millionnaire.engine.core.engine;

import com.millionnaire.engine.config.BoardTemplate;
import com.millionnaire.engine.config.Pricing;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.Tile;
import com.millionnaire.engine.config.TileType;
import com.millionnaire.engine.core.command.GameCommand;
import com.millionnaire.engine.core.event.GameEvent;
import com.millionnaire.engine.core.event.GameEvent.AssetLiquidated;
import com.millionnaire.engine.core.event.GameEvent.AssetMortgaged;
import com.millionnaire.engine.core.event.GameEvent.AssetReclaimed;
import com.millionnaire.engine.core.event.GameEvent.AssetRedeemed;
import com.millionnaire.engine.core.event.GameEvent.BankFinished;
import com.millionnaire.engine.core.event.GameEvent.CashReclaimed;
import com.millionnaire.engine.core.event.GameEvent.CloseReason;
import com.millionnaire.engine.core.event.GameEvent.DebtContinued;
import com.millionnaire.engine.core.event.GameEvent.DebtCreated;
import com.millionnaire.engine.core.event.GameEvent.DebtPaid;
import com.millionnaire.engine.core.event.GameEvent.DebtSegmentStarted;
import com.millionnaire.engine.core.event.GameEvent.DebtSettled;
import com.millionnaire.engine.core.event.GameEvent.LandingAborted;
import com.millionnaire.engine.core.event.GameEvent.LandingFinished;
import com.millionnaire.engine.core.event.GameEvent.LandingStarted;
import com.millionnaire.engine.core.event.GameEvent.LandingStepEntered;
import com.millionnaire.engine.core.event.GameEvent.LiquidationStarted;
import com.millionnaire.engine.core.event.GameEvent.PlayerEliminated;
import com.millionnaire.engine.core.event.GameEvent.PropertyBought;
import com.millionnaire.engine.core.event.GameEvent.PropertyUpgraded;
import com.millionnaire.engine.core.event.GameEvent.PurchaseDeclined;
import com.millionnaire.engine.core.event.GameEvent.RentCharged;
import com.millionnaire.engine.core.event.GameEvent.RentPaid;
import com.millionnaire.engine.core.event.GameEvent.SurrenderBatchEnded;
import com.millionnaire.engine.core.event.GameEvent.SurrenderBatchStarted;
import com.millionnaire.engine.core.event.GameEvent.SurrenderDeferred;
import com.millionnaire.engine.core.event.GameEvent.TurnStageEntered;
import com.millionnaire.engine.core.event.GameEvent.UpgradeSkipped;
import com.millionnaire.engine.core.event.RejectionCode;
import com.millionnaire.engine.core.state.Continuation;
import com.millionnaire.engine.core.state.DebtState;
import com.millionnaire.engine.core.state.DebtPath;
import com.millionnaire.engine.core.state.FeeSource;
import com.millionnaire.engine.core.state.LandingResult;
import com.millionnaire.engine.core.state.Elimination;
import com.millionnaire.engine.core.state.EngineState;
import com.millionnaire.engine.core.state.FlowFrame;
import com.millionnaire.engine.core.state.FlowKind;
import com.millionnaire.engine.core.state.GamePhase;
import com.millionnaire.engine.core.state.GameState;
import com.millionnaire.engine.core.state.LandingState;
import com.millionnaire.engine.core.state.LandingStep;
import com.millionnaire.engine.core.state.LifeState;
import com.millionnaire.engine.core.state.Liquidation;
import com.millionnaire.engine.core.state.OwnableState;
import com.millionnaire.engine.core.state.PlayerState;
import com.millionnaire.engine.core.state.SessionState;
import com.millionnaire.engine.core.state.TurnStage;
import com.millionnaire.engine.core.state.TurnState;
import com.millionnaire.engine.core.state.TurnTrack;
import com.millionnaire.engine.ledger.Ledger;
import com.millionnaire.engine.ledger.TrustedLedgers;
import com.millionnaire.engine.serialize.Immutable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 经济模块（M2）：落点推进器（买 / 放弃、升级、银行、租金）、抵押与赎回、债务（两段手动应急抵押）、破产清算、认输与
 * 延后认输批次、净资产。只经 {@link TurnModule} 的回合窗口入口与 {@link GameModule} 的覆盖窗口入口开关窗口。
 * <p>规则来源：requirements 第 6、7、9、10、16 节；open-decisions 已裁决 2、5、6，D#3、D#8，O5+O6 产品决定，规则补充 v1 的
 * O7/O8/O10/O16；opus-analysis 4.5 / 4.9 / 4.11。M4 免租响应、M5 拍卖与交易在标注处接入。
 */
final class EconomyModule {
    static final String PURCHASE = "PURCHASE";
    static final String UPGRADE = "UPGRADE";
    static final String RENT = "RENT";
    static final String MORTGAGE = "MORTGAGE";
    static final String REDEEM = "REDEEM";
    static final String LIQUIDATION = "LIQUIDATION";
    static final String DEBT_PAYMENT = "DEBT_PAYMENT";
    static final String DEBT_SETTLEMENT = "DEBT_SETTLEMENT";
    static final String RECLAIM = "RECLAIM";
    static final String DEBT_TAG = "DEBT";

    private EconomyModule() {
    }

    // ================================================================ 价值

    /** 资产的标准价值（地产按档位与等级；车站为原价）。 */
    static long standardValue(RuleConfig config, Tile tile, int level) {
        Pricing p = new Pricing(config);
        return tile.type() == TileType.STATION ? p.stationValue() : p.standardValue(tile.tier(), level);
    }

    /** 原价（买价与银行抵押的基数）。 */
    static long basePrice(RuleConfig config, Tile tile) {
        return tile.type() == TileType.STATION ? config.station().price() : config.tier(tile.tier()).basePrice();
    }

    static long emergencyValue(RuleConfig config, Tile tile) {
        Pricing p = new Pricing(config);
        return tile.type() == TileType.STATION ? p.stationEmergencyMortgage() : p.emergencyMortgage(tile.tier());
    }

    static long liquidationValue(RuleConfig config, Tile tile) {
        Pricing p = new Pricing(config);
        return tile.type() == TileType.STATION ? p.stationLiquidationValue() : p.liquidationValue(tile.tier());
    }

    /**
     * 净资产（requirements 第 16 节，O26）：现金 + 未抵押地产标准价值 + 未抵押车站原价 + 已抵押资产（标准价值 − 赎回本金）。
     * 赎回本金为该次抵押实得金额，不含 10% 手续费。
     */
    static long netWorth(RuleConfig config, GameState g, String playerId) {
        BoardTemplate board = LobbyModule.board(config, g.settings());
        long total = g.ledger().cash(playerId);
        for (OwnableState o : g.board().ownedBy(playerId)) {
            long std = standardValue(config, board.tiles().get(o.tile()), o.level());
            total = Math.addExact(total, o.mortgaged() ? std - o.principal() : std);
        }
        return total;
    }

    /** 车站租金的计数：所有者持有的<b>未抵押</b>车站数（D#3）。 */
    static int countedStations(BoardTemplate board, GameState g, String owner) {
        return (int) g.board().ownedBy(owner).stream()
                .filter(o -> board.tiles().get(o.tile()).type() == TileType.STATION && !o.mortgaged()).count();
    }

    static long rent(RuleConfig config, BoardTemplate board, GameState g, OwnableState o) {
        Tile tile = board.tiles().get(o.tile());
        Pricing p = new Pricing(config);
        return tile.type() == TileType.STATION ? p.stationRent(countedStations(board, g, o.owner())) : p.rent(tile.tier(), o.level());
    }

    // ================================================================ 落点推进器

    /** 本步刚落下（非监狱）：开始落点推进器。leadMs 为移动动画，算在下一个窗口之前。 */
    static void land(DecisionContext<SessionState> ctx, int tile, long leadMs) {
        GameState g = game(ctx);
        long id = Math.addExact(g.turn().lastLandingId(), 1);
        ctx.emit(new LandingStarted(id, g.turn().currentPlayer(), tile, g.turn().chain().chainId()));
        resolve(ctx, leadMs);
    }

    /**
     * 落点当时必须处理的第一项步骤（E1，纯函数，决策与演化共用）：他人未抵押地产 → 缴租；无主且买得起（或配置要求买不起也开窗口）→ 买 / 放弃
     * （指定拍卖地的"发起拍卖"留给 M5）；自己的可升级地产 → 升级；银行格且未到全局时限 → 银行；其余 → 无。
     */
    static LandingStep requiredStep(RuleConfig config, GameState g, String player, int tileIndex) {
        BoardTemplate board = LobbyModule.board(config, g.settings());
        Tile tile = board.tiles().get(tileIndex);
        return switch (tile.type()) {
            case PROPERTY, STATION -> {
                OwnableState o = g.board().ownable(tileIndex).orElseThrow();
                if (o.owner() == null) {
                    // 正式配置下买不起也开购买窗口（界面显示价格、购买按钮置灰并提示现金不足）；购买命令仍按现金拒绝
                    yield config.economy().offerUnaffordablePurchase()
                            || g.ledger().available(player) >= basePrice(config, tile) ? LandingStep.BUY : null;
                } else if (o.owner().equals(player)) {
                    yield canUpgrade(config, board, g, player, o) ? LandingStep.UPGRADE : null;
                }
                // 持有免租卡时先问是否使用（响应先于费用成立与现金不足判定，O4）
                yield o.mortgaged() ? null : CardModule.rentResponseDue(config, g, tileIndex) ? LandingStep.RESPONSE : LandingStep.RENT;
            }
            // O16：全局到时后不允许银行常规抵押 / 赎回（阶段表 BANK.drainingAllowed = false），因此不开银行窗口
            case BANK -> g.phase() == GamePhase.RUNNING || StageTable.rule(StageTable.Point.BANK).drainingAllowed()
                    ? LandingStep.BANK : null;
            case EVENT -> g.turn().chain() != null && !g.turn().chain().eventDrawn() ? LandingStep.EVENT : null;
            // 游戏区：至少两名存活者才启动虎口拔牙（#15）
            case GAME_ZONE -> MinigameModule.eligible(config, g, tileIndex) ? LandingStep.MINIGAME : null;
            default -> null;
        };
    }

    /** 按落点的"下一项必须步骤"推进：开决策窗口、缴租（可能进入债务），或结束落点。 */
    private static void resolve(DecisionContext<SessionState> ctx, long leadMs) {
        LandingState l = game(ctx).turn().landing();
        if (l.next() == null) {
            finishLanding(ctx, leadMs);
            return;
        }
        switch (LandingRules.rule(l.next()).execution()) {
            case WINDOW -> {
                // 托管 / 掉线 / 暂离的持卡者立即自动使用免租卡，不开窗口（O4）
                if (l.next() == LandingStep.RESPONSE && game(ctx).player(game(ctx).turn().currentPlayer()).orElseThrow().automated()) {
                    CardModule.autoWaive(ctx, leadMs);
                } else {
                    openStep(ctx, l.next(), leadMs);
                }
            }
            case RENT -> chargeRent(ctx, game(ctx).board().ownable(l.tile()).orElseThrow(), leadMs);
            case EFFECT -> EventModule.performEffect(ctx);
            case FLOW -> {
                if (l.next() == LandingStep.AUCTION) {
                    AuctionModule.beginLand(ctx, leadMs);
                } else {
                    MinigameModule.start(ctx, leadMs);
                }
            }
        }
    }

    static boolean canUpgrade(RuleConfig config, BoardTemplate board, GameState g, String player, OwnableState o) {
        Tile tile = board.tiles().get(o.tile());
        return tile.type() == TileType.PROPERTY && player.equals(o.owner()) && !o.mortgaged() && o.lockedBy() == null
                && o.level() < config.economy().maxLevel()
                && g.ledger().available(player) >= config.tier(tile.tier()).upgradeCost();
    }

    /** 买下之后：若可升级则必须开升级窗口（PropertyBought 按任务表消费 BUY 并追加后续任务）；M4 建造卡在此之后接入。 */
    private static void offerUpgrade(DecisionContext<SessionState> ctx, long leadMs) {
        resolve(ctx, leadMs);
    }

    private static void openStep(DecisionContext<SessionState> ctx, LandingStep step, long leadMs) {
        GameState g = game(ctx);
        LandingState l = g.turn().landing();
        ctx.emit(new LandingStepEntered(l.landingId(), step, 0, l.cursor()));
        StageTable.Rule rule = StageTable.rule(StageTable.landingPoint(step));
        TurnModule.openLandingWindow(ctx, leadMs, StageTable.durationMs(ctx.config(), g, rule.duration()),
                new Continuation.ResumeLanding(g.turn().turnNo(), l.landingId()));
    }

    /** 只完成当前落点，保留行动链；重定向应接着移动，不能先发 TurnEnded。 */
    static void completeLanding(DecisionContext<SessionState> ctx) {
        ctx.emit(new LandingFinished(game(ctx).turn().landing().landingId()));
    }

    static void finishLanding(DecisionContext<SessionState> ctx, long leadMs) {
        completeLanding(ctx);
        // 落点结算后的用卡阶段（15 秒，可直接结束回合）：只在手动玩家还有机会、手里有此刻可用的卡时开启
        if (CardModule.offerPostLanding(ctx.config(), game(ctx))) {
            TurnModule.openDecision(ctx, new Continuation.EndTurn(game(ctx).turn().turnNo()), leadMs,
                    ctx.config().timing().decisionWindowMs());
            return;
        }
        TurnModule.endTurn(ctx, leadMs);
    }

    /** 续接 ResumeLanding：债务流程返回后，按落点的"下一项必须步骤"继续（M2 中租金结清后为无，落点结束）。 */
    static void resumeLanding(DecisionContext<SessionState> ctx, long leadMs) {
        resolve(ctx, leadMs);
    }

    /** 落点决策窗口的超时 / 自动动作（窗口已由回合模块关闭）：按阶段数据表选择动作。 */
    static void landingDefault(DecisionContext<SessionState> ctx) {
        GameState g = game(ctx);
        PlayerState p = g.player(g.turn().currentPlayer()).orElseThrow();
        StageTable.Rule rule = StageTable.rule(StageTable.landingPoint(g.turn().landing().step()));
        boolean auto = p.automated();
        StageTable.Action action = !auto ? rule.onTimeout()
                : p.control() != com.millionnaire.engine.core.state.ControlMode.MANUAL ? rule.hostedPolicy() : rule.offlinePolicy();
        apply(ctx, action, auto);
    }

    private static void apply(DecisionContext<SessionState> ctx, StageTable.Action action, boolean auto) {
        GameState g = game(ctx);
        String player = g.turn().currentPlayer();
        LandingState l = g.turn().landing();
        Tile tile = board(ctx).tiles().get(l.tile());
        switch (action) {
            case BUY_IF_AFFORDABLE -> {
                if (g.ledger().available(player) >= basePrice(ctx.config(), tile)) {
                    buy(ctx);
                } else {
                    decline(ctx, auto);
                }
            }
            case DECLINE -> decline(ctx, auto);
            case UPGRADE_IF_AFFORDABLE -> {
                OwnableState o = g.board().ownable(l.tile()).orElseThrow();
                if (canUpgrade(ctx.config(), board(ctx), g, player, o)) {
                    upgrade(ctx);
                } else {
                    skip(ctx, auto);
                }
            }
            case SKIP -> skip(ctx, auto);
            case FINISH -> {
                ctx.emit(new BankFinished(player, auto));
                resolve(ctx, 0);
            }
            case DRAW_EVENT -> EventModule.draw(ctx, true);
            case USE_RESPONSE -> CardModule.waiveRent(ctx, true, 0);
            case DECLINE_RESPONSE -> CardModule.declineWaiver(ctx, true);
            case DISCARD_NEW -> EventModule.discard(ctx, l.event().newCardIndex(), true);
            case ROLL, NEXT_SEGMENT_OR_BANKRUPT -> throw new IllegalStateException(action + " is not a landing action");
        }
    }

    private static void buy(DecisionContext<SessionState> ctx) {
        GameState g = game(ctx);
        LandingState l = g.turn().landing();
        Tile tile = board(ctx).tiles().get(l.tile());
        ctx.emit(new PropertyBought(g.turn().currentPlayer(), l.tile(), basePrice(ctx.config(), tile)));
        // 买后是否可立即付费升一级由配置决定（旧裁决 6 为可以；2026-10-07 起正式规则为不可以）；建造卡免费升级为 M4 保留
        offerUpgrade(ctx, 0);
    }

    private static void decline(DecisionContext<SessionState> ctx, boolean auto) {
        GameState g = game(ctx);
        ctx.emit(new PurchaseDeclined(g.turn().currentPlayer(), g.turn().landing().tile(), auto));
        resolve(ctx, 0);
    }

    private static void upgrade(DecisionContext<SessionState> ctx) {
        GameState g = game(ctx);
        LandingState l = g.turn().landing();
        OwnableState o = g.board().ownable(l.tile()).orElseThrow();
        Tile tile = board(ctx).tiles().get(l.tile());
        ctx.emit(new PropertyUpgraded(g.turn().currentPlayer(), l.tile(), o.level() + 1,
                ctx.config().tier(tile.tier()).upgradeCost()));
        // 每次最多升一级（requirements 第 6 节）
        resolve(ctx, 0);
    }

    private static void skip(DecisionContext<SessionState> ctx, boolean auto) {
        GameState g = game(ctx);
        ctx.emit(new UpgradeSkipped(g.turn().currentPlayer(), g.turn().landing().tile(), auto));
        resolve(ctx, 0);
    }

    // ================================================================ 租金与债务

    private static void chargeRent(DecisionContext<SessionState> ctx, OwnableState o, long leadMs) {
        GameState g = game(ctx);
        String payer = g.turn().currentPlayer();
        long amount = rent(ctx.config(), board(ctx), g, o);
        // M4 接入点：免租等响应卡先于费用成立（已裁决 6、O4）；M2 没有卡效果，费用直接成立
        ctx.emit(new RentCharged(payer, o.owner(), o.tile(), amount));
        if (game(ctx).ledger().available(payer) >= amount) {
            ctx.emit(new RentPaid(payer, o.owner(), o.tile(), amount));
            resolve(ctx, leadMs);
        } else {
            startDebt(ctx, o.owner(), amount, RENT, leadMs);
        }
    }

    static void chargeFee(DecisionContext<SessionState> ctx, FeeSource source, long leadMs) {
        String payer = game(ctx).turn().currentPlayer();
        ctx.emit(new GameEvent.FeeCharged(payer, source));
        if (game(ctx).ledger().available(payer) >= source.amount()) {
            ctx.emit(new GameEvent.FeePaid(payer, source));
            resolve(ctx, leadMs);
        } else {
            startDebt(ctx, source.creditor(), source.amount(), FeeRules.rule(source.kind()).cause(), leadMs);
        }
    }

    /** 可用于应急抵押的资产应急额合计（未抵押、未被流程锁定）。 */
    static long emergencyCapacity(RuleConfig config, BoardTemplate board, GameState g, String player) {
        long sum = 0;
        for (OwnableState o : g.board().ownedBy(player)) {
            if (!o.mortgaged() && o.lockedBy() == null) {
                sum = Math.addExact(sum, emergencyValue(config, board.tiles().get(o.tile())));
            }
        }
        return sum;
    }

    /**
     * 费用成立而现金不足（已裁决 2、O6）：债务成立即锁定处理路径。确认掉线、暂离或托管 → 直接破产；
     * 现金 + 全部可抵押资产的应急额仍不足 → 立即破产，不开窗口；否则开手动应急抵押窗口（第一段 30 秒）。
     */
    static DebtPath debtPath(RuleConfig config, GameState g, String player, long amount) {
        long capacity = Math.addExact(g.ledger().available(player), emergencyCapacity(config,
                LobbyModule.board(config, g.settings()), g, player));
        return g.player(player).orElseThrow().automated() || capacity < amount
                ? DebtPath.DIRECT_BANKRUPTCY : DebtPath.MANUAL;
    }

    private static void startDebt(DecisionContext<SessionState> ctx, String creditor, long amount, String cause, long leadMs) {
        GameState g = game(ctx);
        String debtor = g.turn().currentPlayer();
        LandingState l = g.turn().landing();
        DebtState debt = new DebtState(Math.addExact(g.turn().lastDebtId(), 1), debtor, creditor, amount, cause,
                0, false, 0, g.turn().track().feeSource(), g.turn().track().debtPath());
        ctx.emit(new DebtCreated(debt));
        if (debt.path() == DebtPath.DIRECT_BANKRUPTCY) {
            bankrupt(ctx, debtor, leadMs);
            return;
        }
        ctx.emit(new LandingStepEntered(l.landingId(), LandingStep.DEBT, amount, l.cursor()));
        ctx.emit(new TurnStageEntered(TurnStage.AWAITING_FLOW, 0,
                new Continuation.ResumeLanding(g.turn().turnNo(), l.landingId()), Math.addExact(ctx.now(), leadMs)));
        openDebtSegment(ctx, 1, leadMs);
    }

    private static void openDebtSegment(DecisionContext<SessionState> ctx, int segment, long leadMs) {
        DebtState d = game(ctx).debt();
        FlowCoordinator.Opened opened = GameModule.openOverlay(ctx, FlowKind.DEBT, d.debtor(), leadMs,
                ctx.config().timing().debtSegmentMs(), DEBT_TAG);
        if (!(opened instanceof FlowCoordinator.Opened.Window w)) {
            throw new IllegalStateException("debt segments always have a positive duration");
        }
        ctx.emit(new DebtSegmentStarted(d.debtId(), segment, w.windowId()));
    }

    /** 债务窗口到期：第一段 → 第二段（弹窗默认继续）；第二段 → 破产。 */
    static void onDebtExpired(DecisionContext<SessionState> ctx, FlowFrame frame) {
        DebtState d = game(ctx).debt();
        if (d == null || d.windowId() != frame.windowId()) {
            throw new IllegalStateException("debt window without a debt");
        }
        FlowCoordinator.close(ctx, GameModule.FLOW, frame.windowId(), CloseReason.EXPIRED);
        if (d.segment() == 1) {
            openDebtSegment(ctx, 2, 0);
        } else {
            bankrupt(ctx, d.debtor(), 0);
        }
    }

    // ================================================================ 命令

    static RejectionCode decide(DecisionContext<SessionState> ctx, GameCommand command) {
        GameState g = game(ctx);
        return switch (command) {
            case GameCommand.EmergencyMortgage c -> debtCommand(ctx, c.actor(), c.windowId(), () -> emergencyMortgage(ctx, c));
            case GameCommand.ContinueDebt c -> debtCommand(ctx, c.actor(), c.windowId(), () -> {
                DebtState d = game(ctx).debt();
                if (d.segment() != 2 || d.continued()) {
                    return RejectionCode.WRONG_STAGE;
                }
                ctx.emit(new DebtContinued(d.debtId()));
                return null;
            });
            case GameCommand.DeclareBankruptcy c -> debtCommand(ctx, c.actor(), c.windowId(), () -> {
                bankrupt(ctx, c.actor(), 0);
                return null;
            });
            case GameCommand.Surrender c -> surrender(ctx, c);
            default -> turnWindowCommand(ctx, command, g);
        };
    }

    /** 回合窗口内的经济命令：先核对窗口与阶段数据表，再按命令处理。 */
    private static RejectionCode turnWindowCommand(DecisionContext<SessionState> ctx, GameCommand command, GameState g) {
        String actor = command.actor();
        long windowId = switch (command) {
            case GameCommand.BuyProperty c -> c.windowId();
            case GameCommand.DeclinePurchase c -> c.windowId();
            case GameCommand.StartLandAuction c -> c.windowId();
            case GameCommand.UpgradeProperty c -> c.windowId();
            case GameCommand.SkipUpgrade c -> c.windowId();
            case GameCommand.BankMortgage c -> c.windowId();
            case GameCommand.Redeem c -> c.windowId();
            case GameCommand.FinishBank c -> c.windowId();
            default -> throw new IllegalArgumentException("not an economy command: " + command);
        };
        RejectionCode why = TurnModule.checkTurnWindow(ctx, actor, windowId);
        if (why != null) {
            return why;
        }
        StageTable.Rule rule = StageTable.rule(StageTable.turnPoint(g));
        if (!rule.allows(command)) {
            return RejectionCode.WRONG_STAGE;
        }
        if (g.phase() == GamePhase.DRAINING && !rule.drainingAllowed()) {
            return RejectionCode.DRAINING;
        }
        BoardTemplate board = board(ctx);
        LandingState l = g.turn().landing();
        return switch (command) {
            case GameCommand.BuyProperty c -> {
                if (g.ledger().available(actor) < basePrice(ctx.config(), board.tiles().get(l.tile()))) {
                    yield RejectionCode.INSUFFICIENT_CASH;
                }
                TurnModule.closeDecision(ctx);
                buy(ctx);
                yield null;
            }
            case GameCommand.DeclinePurchase c -> {
                TurnModule.closeDecision(ctx);
                decline(ctx, false);
                yield null;
            }
            case GameCommand.StartLandAuction c -> AuctionModule.chooseLandAuction(ctx, c);
            case GameCommand.UpgradeProperty c -> {
                if (!canUpgrade(ctx.config(), board, g, actor, g.board().ownable(l.tile()).orElseThrow())) {
                    yield RejectionCode.INSUFFICIENT_CASH;
                }
                TurnModule.closeDecision(ctx);
                upgrade(ctx);
                yield null;
            }
            case GameCommand.SkipUpgrade c -> {
                TurnModule.closeDecision(ctx);
                skip(ctx, false);
                yield null;
            }
            case GameCommand.FinishBank c -> {
                TurnModule.closeDecision(ctx);
                ctx.emit(new BankFinished(actor, false));
                resolve(ctx, 0);
                yield null;
            }
            case GameCommand.BankMortgage c -> bankMortgage(ctx, actor, c.tile());
            case GameCommand.Redeem c -> redeem(ctx, actor, c.tile());
            default -> throw new IllegalArgumentException("not an economy command: " + command);
        };
    }

    private static RejectionCode ownAsset(GameState g, BoardTemplate board, String actor, int tile) {
        if (tile < 0 || tile >= board.size() || g.board().ownable(tile).isEmpty()) {
            return RejectionCode.INVALID_TILE;
        }
        OwnableState o = g.board().ownable(tile).get();
        if (!actor.equals(o.owner())) {
            return RejectionCode.NOT_OWNER;
        }
        return o.lockedBy() != null ? RejectionCode.ASSET_LOCKED : null;
    }

    /** O10 / D#8：银行格、自己回合的合法操作窗口、无债务、未到全局时限时，按原价 100% 抵押；窗口计时不刷新。 */
    private static RejectionCode bankMortgage(DecisionContext<SessionState> ctx, String actor, int tile) {
        GameState g = game(ctx);
        BoardTemplate board = board(ctx);
        if (g.phase() != GamePhase.RUNNING) {
            return RejectionCode.DRAINING;
        }
        if (board.tiles().get(g.player(actor).orElseThrow().position()).type() != TileType.BANK) {
            return RejectionCode.NOT_AT_BANK;
        }
        RejectionCode why = ownAsset(g, board, actor, tile);
        if (why != null) {
            return why;
        }
        if (g.board().ownable(tile).orElseThrow().mortgaged()) {
            return RejectionCode.MORTGAGED;
        }
        long principal = new Pricing(ctx.config()).bankMortgage(basePrice(ctx.config(), board.tiles().get(tile)));
        ctx.emit(new AssetMortgaged(actor, tile, principal, false));
        return null;
    }

    /** 赎回：归还本金；在银行格免手续费，其他位置加本金的 10%；可用现金须足额；全局到时后不允许（O16）。 */
    private static RejectionCode redeem(DecisionContext<SessionState> ctx, String actor, int tile) {
        GameState g = game(ctx);
        BoardTemplate board = board(ctx);
        if (g.phase() != GamePhase.RUNNING) {
            return RejectionCode.DRAINING;
        }
        RejectionCode why = ownAsset(g, board, actor, tile);
        if (why != null) {
            return why;
        }
        OwnableState o = g.board().ownable(tile).orElseThrow();
        if (!o.mortgaged()) {
            return RejectionCode.NOT_MORTGAGED;
        }
        boolean atBank = board.tiles().get(g.player(actor).orElseThrow().position()).type() == TileType.BANK;
        long fee = new Pricing(ctx.config()).redeemFee(o.principal(), atBank);
        if (g.ledger().available(actor) < Math.addExact(o.principal(), fee)) {
            return RejectionCode.INSUFFICIENT_CASH;
        }
        ctx.emit(new AssetRedeemed(actor, tile, o.principal(), fee));
        return null;
    }

    /** 债务窗口命令：债务人本人、当前债务窗口（路径已锁定为手动，不看控制模式）。 */
    private static RejectionCode debtCommand(DecisionContext<SessionState> ctx, String actor, long windowId,
                                             java.util.function.Supplier<RejectionCode> body) {
        GameState g = game(ctx);
        DebtState d = g.debt();
        if (d == null || d.windowId() == 0) {
            return RejectionCode.NO_ACTIVE_WINDOW;
        }
        if (actor == null || !actor.equals(d.debtor())) {
            return RejectionCode.NOT_YOUR_WINDOW;
        }
        if (windowId != d.windowId()) {
            return RejectionCode.WINDOW_MISMATCH;
        }
        RejectionCode why = FlowCoordinator.checkWindow(g.flow(), windowId, actor, ctx.now());
        return why != null ? why : body.get();
    }

    /** 应急抵押：按应急比例（有债务时即使在银行也如此，D#8）；筹足即付（一次付清，O6）。 */
    private static RejectionCode emergencyMortgage(DecisionContext<SessionState> ctx, GameCommand.EmergencyMortgage c) {
        GameState g = game(ctx);
        BoardTemplate board = board(ctx);
        RejectionCode why = ownAsset(g, board, c.actor(), c.tile());
        if (why != null) {
            return why;
        }
        if (g.board().ownable(c.tile()).orElseThrow().mortgaged()) {
            return RejectionCode.MORTGAGED;
        }
        ctx.emit(new AssetMortgaged(c.actor(), c.tile(), emergencyValue(ctx.config(), board.tiles().get(c.tile())), true));
        DebtState d = game(ctx).debt();
        if (game(ctx).ledger().available(d.debtor()) >= d.amount()) {
            ctx.emit(new DebtPaid(d.debtId(), d.amount()));
            // 关闭债务窗口：统一收尾（延后认输批次 → 续接 ResumeLanding）
            GameModule.closeOverlay(ctx, d.windowId(), CloseReason.ACTED);
        }
        return null;
    }

    // ================================================================ 破产、认输与淘汰

    /**
     * 破产清算（requirements 第 10 节，经 O5+O6 产品决定修改）：未抵押资产按应急比例变现，已抵押资产直接回收；
     * 现金偿债，不超过欠款，有多少给多少；剩余现金系统回收；产权、等级、抵押清空。之后统一收尾。
     */
    static void bankrupt(DecisionContext<SessionState> ctx, String playerId, long leadMs) {
        GameState g = game(ctx);
        DebtState d = g.debt();
        if (d == null || !d.debtor().equals(playerId)) {
            throw new IllegalStateException("bankruptcy without a debt of " + playerId);
        }
        if (d.windowId() != 0 && g.flow().frame(d.windowId()).isPresent()) {
            FlowCoordinator.close(ctx, GameModule.FLOW, d.windowId(), CloseReason.CANCELLED);
        }
        ctx.emit(new LiquidationStarted(playerId, d.debtId(), LifeState.BANKRUPT));
        eliminate(ctx, playerId, LifeState.BANKRUPT);
        BoardTemplate board = board(ctx);
        for (OwnableState o : game(ctx).board().ownedBy(playerId)) {
            if (o.mortgaged()) {
                ctx.emit(new AssetReclaimed(playerId, o.tile()));
            } else {
                ctx.emit(new AssetLiquidated(playerId, o.tile(), liquidationValue(ctx.config(), board.tiles().get(o.tile()))));
            }
        }
        long cash = game(ctx).ledger().cash(playerId);
        ctx.emit(new DebtSettled(d.debtId(), d.creditor(), Math.min(cash, d.amount())));
        ctx.emit(new CashReclaimed(playerId, game(ctx).ledger().cash(playerId)));
        afterElimination(ctx, leadMs);
    }

    private static void eliminate(DecisionContext<SessionState> ctx, String playerId, LifeState how) {
        GameState g = game(ctx);
        long batch = g.turn().track().openBatch() != 0 ? g.turn().track().openBatch() : maxBatch(g) + 1;
        ctx.emit(new PlayerEliminated(playerId, how, new Elimination(maxSeq(g) + 1, batch, netWorth(ctx.config(), g, playerId))));
    }

    static long maxSeq(GameState g) {
        return g.players().stream().filter(p -> p.elimination() != null).mapToLong(p -> p.elimination().seq()).max().orElse(0);
    }

    static long maxBatch(GameState g) {
        return g.players().stream().filter(p -> p.elimination() != null).mapToLong(p -> p.elimination().batch()).max().orElse(0);
    }

    /** 认输清算（无欠款）：现金与资产全部由系统回收，不奖励他人（requirements 第 10 节）。 */
    private static void surrenderNow(DecisionContext<SessionState> ctx, String playerId) {
        ctx.emit(new LiquidationStarted(playerId, 0, LifeState.SURRENDERED));
        eliminate(ctx, playerId, LifeState.SURRENDERED);
        for (OwnableState o : game(ctx).board().ownedBy(playerId)) {
            ctx.emit(new AssetReclaimed(playerId, o.tile()));
        }
        ctx.emit(new CashReclaimed(playerId, game(ctx).ledger().cash(playerId)));
    }

    /**
     * 认输（O7）：欠款中的债务人 → 等同确认破产；正在进行的流程会受其影响的参与者（债权人、覆盖流程的所有者）→ 延后到流程结束；
     * 当前回合玩家在覆盖流程进行中认输同样延后（流程返回时再结束其回合）；其他情况立即生效，当前玩家认输立即结束其回合。
     */
    private static RejectionCode surrender(DecisionContext<SessionState> ctx, GameCommand.Surrender c) {
        GameState g = game(ctx);
        if (c.gameNo() != g.gameNo()) {
            return RejectionCode.GAME_MISMATCH;
        }
        Optional<PlayerState> p = c.actor() == null ? Optional.empty() : g.player(c.actor());
        if (p.isEmpty()) {
            return RejectionCode.NOT_MEMBER;
        }
        if (!p.get().alive()) {
            return RejectionCode.NOT_ALIVE;
        }
        DebtState d = g.debt();
        if (d != null && d.debtor().equals(c.actor())) {
            bankrupt(ctx, c.actor(), 0);
            return null;
        }
        if (involvedInRunningFlow(g, c.actor())) {
            if (g.pendingSurrenders().contains(c.actor())) {
                return RejectionCode.UNCHANGED;
            }
            ctx.emit(new SurrenderDeferred(c.actor()));
            return null;
        }
        boolean current = c.actor().equals(g.turn().currentPlayer());
        if (current && !flowsRunning(g)) {
            TurnModule.abortTurnWindows(ctx);
        }
        // 当前玩家在他人流程进行中认输：立即清算，回合在流程返回后结束（settle）
        surrenderNow(ctx, c.actor());
        afterElimination(ctx, 0);
        return null;
    }

    /**
     * 是否会影响正在进行的流程的结果（待确认默认 5：只按各流程<b>显式声明的参与角色</b>判断，不以"当前玩家"兜底）：
     * 债务 → 债务人与债权人；拍卖 → 全体存活者（<b>仅为 M2 占位测试用，范围宽于 O7，不可带入正式规则</b>：M5 必须按卖家 /
     * 发起人 / 实际出价者重新定义）；交易及其他覆盖流程 → 窗口所有者（M5 交易须覆盖卖家与买家，小游戏覆盖全体参与者）。
     */
    static boolean involvedInRunningFlow(GameState g, String playerId) {
        if (g.debt() != null && (playerId.equals(g.debt().creditor()) || playerId.equals(g.debt().debtor()))) {
            return true;
        }
        // 待对方响应的道具：使用者与目标所有者
        var effect = g.cards().effect();
        if (effect != null && (playerId.equals(effect.actor()) || playerId.equals(effect.target()))) {
            return true;
        }
        // 拍卖：卖家 / 发起人与出过价的玩家（M5 定义，取代 M2 的"全体存活者"占位）
        if (AuctionModule.involved(g, playerId) || TradeModule.involved(g, playerId)) {
            return true;
        }
        // 小游戏：全体参与者（#15 中途认输延后到小游戏结束清算）
        if (g.minigame() != null && g.minigame().participants().contains(playerId)) {
            return true;
        }
        for (FlowFrame f : g.flow().frames()) {
            if (f.kind() == FlowKind.AUCTION && g.auction() == null && g.player(playerId).map(PlayerState::alive).orElse(false)
                    || f.kind() != FlowKind.TURN && f.kind() != FlowKind.DEBT && playerId.equals(f.owner())) {
                return true;
            }
        }
        return false;
    }

    /** 是否仍有流程在进行（债务或任一覆盖窗口）。 */
    static boolean flowsRunning(GameState g) {
        return g.debt() != null || g.minigame() != null || g.auction() != null || g.trade() != null
                || g.flow().frames().stream().anyMatch(f -> f.kind() != FlowKind.TURN);
    }

    /** 结束原因（E5）：进入 DRAINING 后固定为到时结束；否则零存活 → 全员淘汰，一人 → 最后存活者。 */
    static String endReason(GameState g) {
        if (g.phase() == GamePhase.DRAINING) {
            return "TIME_UP";
        }
        return g.alive().isEmpty() ? "ALL_ELIMINATED" : "LAST_SURVIVOR";
    }

    /** 淘汰后的统一收尾（见 {@link #settle}）。 */
    static void afterElimination(DecisionContext<SessionState> ctx, long leadMs) {
        settle(ctx, leadMs);
    }

    /** 流程返回时的统一收尾入口（回合模块调用）；返回 true 表示已接管后续推进。 */
    static boolean processDeferred(DecisionContext<SessionState> ctx) {
        return settle(ctx, 0);
    }

    /**
     * 淘汰或流程结束后的统一收尾（E2、E5）：
     * <ol>
     *   <li>存在已接受的延后认输：流程仍在进行 → 什么都不做（不判胜、不越过流程）；流程已全部结束 → 同一批按收到顺序清算；</li>
     *   <li>存活 ≤ 1 → 结束对局（原因见 {@link #endReason}，DRAINING 后固定为到时结束）；</li>
     *   <li>当前玩家已被淘汰：仍有流程 → 等流程返回；否则中止落点、结束其回合并轮转。</li>
     * </ol>
     * 返回 true 表示已接管后续推进（结束对局或结束当前回合）。
     */
    static boolean settle(DecisionContext<SessionState> ctx, long leadMs) {
        GameState g = game(ctx);
        if (!g.pendingSurrenders().isEmpty()) {
            if (flowsRunning(g)) {
                return false;
            }
            long batch = maxBatch(g) + 1;
            ctx.emit(new SurrenderBatchStarted(batch));
            for (String id : List.copyOf(g.pendingSurrenders())) {
                surrenderNow(ctx, id);
            }
            ctx.emit(new SurrenderBatchEnded(batch));
            g = game(ctx);
        }
        if (g.alive().size() <= 1) {
            // 零存活时按最后一批的批次前净资产排名（O7/O8，规则补充 v1）
            TurnModule.finish(ctx, endReason(g));
            return true;
        }
        if (!g.player(g.turn().currentPlayer()).orElseThrow().alive()) {
            if (flowsRunning(g)) {
                return false;
            }
            TurnModule.abortTurnWindows(ctx);
            if (game(ctx).turn().landing() != null) {
                ctx.emit(new LandingAborted(game(ctx).turn().landing().landingId()));
            }
            TurnModule.endTurn(ctx, leadMs);
            return true;
        }
        return false;
    }

    // ================================================================ 演化（核对归属、阶段、金额 / 资产、顺序、任务关联）

    static boolean handles(GameEvent e) {
        return e instanceof LandingStarted || e instanceof LandingStepEntered || e instanceof LandingFinished
                || e instanceof LandingAborted
                || e instanceof PropertyBought || e instanceof PurchaseDeclined || e instanceof PropertyUpgraded
                || e instanceof UpgradeSkipped || e instanceof RentCharged || e instanceof RentPaid
                || e instanceof AssetMortgaged || e instanceof AssetRedeemed || e instanceof BankFinished
                || e instanceof DebtCreated || e instanceof DebtSegmentStarted || e instanceof DebtContinued
                || e instanceof DebtPaid || e instanceof LiquidationStarted || e instanceof AssetLiquidated
                || e instanceof AssetReclaimed || e instanceof DebtSettled || e instanceof CashReclaimed
                || e instanceof PlayerEliminated || e instanceof SurrenderDeferred || e instanceof SurrenderBatchStarted
                || e instanceof SurrenderBatchEnded;
    }

    static GameState evolve(GameState g, GameEvent event, RuleConfig rules) {
        TurnState t = g.turn();
        TurnTrack k = t.track();
        LandingState l = t.landing();
        BoardTemplate board = LobbyModule.board(rules, g.settings());
        Pricing pricing = new Pricing(rules);
        return switch (event) {
            case LandingStarted e -> {
                check(current(g, e.playerId()) && l == null && k.landedTile() == e.tile() && e.landingId() == t.lastLandingId() + 1
                        && g.player(e.playerId()).orElseThrow().position() == e.tile(), "landing start mismatch");
                check(t.chain() != null && e.chainId() == t.chain().chainId(), "landing belongs to another move chain");
                yield g.withTurn(t.withLanding(new LandingState(e.landingId(), e.chainId(), e.tile(), null, 0, false,
                                LandingRules.initial(rules, g, e.playerId(), e.tile()),
                                LandingRules.initial(rules, g, e.playerId(), e.tile()).stream().map(ignored -> -1).toList(),
                                List.of(), 0, false))
                        .withTrack(k.landingTaken()));
            }
            case LandingStepEntered e -> {
                check(l != null && l.landingId() == e.landingId() && e.cursor() == l.cursor(), "landing step for another landing or cursor");
                boolean ok = LandingRules.canEnter(rules, g, l, e.step(), e.payment());
                check(ok, "landing step " + e.step() + " not legal here (next " + l.next() + ")");
                boolean decision = !e.step().awaitsFlow();
                yield g.withTurn(t.withLanding(l.withStep(e.step(), e.payment()).decision(decision)));
            }
            case LandingFinished e -> {
                check(l != null && l.landingId() == e.landingId() && l.cursor() == l.tasks().size() && !l.decisionOpen() && l.pendingPayment() == 0
                        && k.pendingCharge() == 0 && (g.debt() == null || g.debt().source().landingId() != l.landingId()),
                        "landing finished before its required steps were done (next " + (l == null ? null : l.next())
                                + ", decision open " + (l != null && l.decisionOpen()) + ")");
                yield g.withTurn(t.withLanding(null));
            }
            case LandingAborted e -> {
                check(l != null && l.landingId() == e.landingId() && !g.player(t.currentPlayer()).orElseThrow().alive()
                        && k.pendingCharge() == 0 && g.debt() == null, "a landing can only be aborted by eliminating its player");
                yield g.withTurn(t.withLanding(null));
            }
            case PropertyBought e -> {
                OwnableState o = g.board().ownable(e.tile()).orElse(null);
                check(current(g, e.playerId()) && l != null && l.step() == LandingStep.BUY && l.decisionOpen() && l.tile() == e.tile()
                        && o != null && o.owner() == null && e.price() == basePrice(rules, board.tiles().get(e.tile())),
                        "purchase mismatch");
                Ledger ledger = g.ledger().transfer(e.playerId(), Ledger.SYSTEM, e.price(), PURCHASE, "tile-" + e.tile());
                GameState bought = g.withLedger(ledger).withBoard(g.board().with(o.owned(e.playerId())))
                        .withCards(g.cards().bought(e.tile()));
                // 买下之后的下一步：配置允许且可升级时开升级窗口（正式规则不开）
                yield bought.withTurn(t.withLanding(LandingRules.consume(rules, bought, l, LandingResult.BOUGHT)));
            }
            case PurchaseDeclined e -> {
                check(current(g, e.playerId()) && l != null && l.step() == LandingStep.BUY && l.decisionOpen()
                        && l.tile() == e.tile(), "decline mismatch");
                yield g.withTurn(t.withLanding(LandingRules.consume(rules, g, l, LandingResult.DECLINED)));
            }
            case PropertyUpgraded e -> {
                OwnableState o = g.board().ownable(e.tile()).orElse(null);
                Tile tile = board.tiles().get(e.tile());
                check(current(g, e.playerId()) && l != null && l.step() == LandingStep.UPGRADE && l.decisionOpen()
                        && l.tile() == e.tile() && o != null && canUpgrade(rules, board, g, e.playerId(), o)
                        && e.level() == o.level() + 1 && e.cost() == rules.tier(tile.tier()).upgradeCost(), "upgrade mismatch");
                Ledger ledger = g.ledger().transfer(e.playerId(), Ledger.SYSTEM, e.cost(), UPGRADE, "tile-" + e.tile());
                GameState upgraded = g.withLedger(ledger).withBoard(g.board().with(o.level(e.level())));
                yield upgraded.withTurn(t.withLanding(LandingRules.consume(rules, upgraded, l, LandingResult.UPGRADED)));
            }
            case UpgradeSkipped e -> {
                check(current(g, e.playerId()) && l != null && l.step() == LandingStep.UPGRADE && l.decisionOpen()
                        && l.tile() == e.tile(), "skip mismatch");
                yield g.withTurn(t.withLanding(LandingRules.consume(rules, g, l, LandingResult.SKIPPED)));
            }
            case RentCharged e -> {
                OwnableState o = g.board().ownable(e.tile()).orElse(null);
                check(current(g, e.payer()) && l != null && l.next() == LandingStep.RENT && l.tile() == e.tile() && o != null
                        && o.owner() != null && o.owner().equals(e.owner()) && !e.owner().equals(e.payer()) && !o.mortgaged()
                        && k.pendingCharge() == 0 && e.amount() == rent(rules, board, g, o), "rent charge mismatch");
                FeeSource source = new FeeSource(FeeSource.Kind.RENT, l.landingId(), l.cursor(), e.tile(), e.owner(), e.amount());
                yield g.withTurn(t.withLanding(l.withStep(null, e.amount()))
                        .withTrack(k.fee(source, debtPath(rules, g, e.payer(), e.amount()))));
            }
            case RentPaid e -> {
                // RentCharged is the sole producer of pendingCharge, and fixes RENT/source/cursor/creditor together.
                // No intervening event changes its source; amount/tile/owner and LandingRules.consume remain authoritative.
                check(current(g, e.payer()) && l != null && l.tile() == e.tile() && k.pendingCharge() == e.amount()
                        && e.amount() > 0 && g.debt() == null
                        && g.board().ownable(e.tile()).map(o -> e.owner().equals(o.owner())).orElse(false),
                        "rent payment mismatch");
                Ledger ledger = g.ledger().transfer(e.payer(), e.owner(), e.amount(), RENT, "landing-" + l.landingId());
                GameState paid = g.withLedger(ledger);
                yield paid.withTurn(t.withLanding(LandingRules.consume(rules, paid, l, LandingResult.PAID)).withTrack(k.charge(0)));
            }
            case AssetMortgaged e -> {
                OwnableState o = g.board().ownable(e.tile()).orElse(null);
                check(o != null && e.playerId().equals(o.owner()) && !o.mortgaged() && o.lockedBy() == null
                        && g.player(e.playerId()).orElseThrow().alive(), "mortgage of a foreign or mortgaged asset");
                Tile tile = board.tiles().get(e.tile());
                if (e.emergency()) {
                    check(g.debt() != null && g.debt().debtor().equals(e.playerId()) && g.debt().segment() >= 1
                            && e.principal() == emergencyValue(rules, tile), "emergency mortgage mismatch");
                } else {
                    PlayerState p = g.player(e.playerId()).orElseThrow();
                    check(g.debt() == null && current(g, e.playerId()) && g.phase() == GamePhase.RUNNING
                            && board.tiles().get(p.position()).type() == TileType.BANK
                            && e.principal() == pricing.bankMortgage(basePrice(rules, tile)), "bank mortgage mismatch");
                }
                Ledger ledger = g.ledger().transfer(Ledger.SYSTEM, e.playerId(), e.principal(), MORTGAGE, "tile-" + e.tile());
                yield g.withLedger(ledger).withBoard(g.board().with(o.mortgage(e.principal())));
            }
            case AssetRedeemed e -> {
                OwnableState o = g.board().ownable(e.tile()).orElse(null);
                PlayerState p = g.player(e.playerId()).orElseThrow();
                boolean atBank = board.tiles().get(p.position()).type() == TileType.BANK;
                check(o != null && e.playerId().equals(o.owner()) && o.mortgaged() && o.principal() == e.principal()
                        && e.fee() == pricing.redeemFee(e.principal(), atBank) && current(g, e.playerId()) && g.debt() == null
                        && g.phase() == GamePhase.RUNNING, "redeem mismatch");
                Ledger ledger = g.ledger().transfer(e.playerId(), Ledger.SYSTEM, Math.addExact(e.principal(), e.fee()), REDEEM,
                        "tile-" + e.tile());
                yield g.withLedger(ledger).withBoard(g.board().with(o.redeemed()));
            }
            case BankFinished e -> {
                check(current(g, e.playerId()) && l != null && l.step() == LandingStep.BANK && l.decisionOpen(),
                        "bank finish mismatch");
                yield g.withTurn(t.withLanding(LandingRules.consume(rules, g, l, LandingResult.BANK_FINISHED)));
            }
            case DebtCreated e -> {
                DebtState d = e.debt();
                check(g.debt() == null && l != null && d.debtId() == Math.addExact(t.lastDebtId(), 1) && current(g, d.debtor())
                        && k.pendingCharge() == d.amount() && d.amount() > g.ledger().available(d.debtor())
                        && d.source() != null && FeeRules.rule(d.source().kind()).cause().equals(d.cause())
                        && FeeRules.valid(rules, g, l, d.source()) && java.util.Objects.equals(d.creditor(), d.source().creditor())
                        && java.util.Objects.equals(d.source(), k.feeSource()) && d.source() != null
                        && d.source().landingId() == l.landingId() && d.source().cursor() == l.cursor()
                        && d.path() != null && d.path() == k.debtPath()
                        && d.segment() == 0 && !d.continued() && d.windowId() == 0, "debt creation mismatch");
                yield g.withDebt(d).withTurn(t.debtAllocated(d.debtId()).withTrack(k.charge(0)
                        .bankruptcy(d.path() == DebtPath.DIRECT_BANKRUPTCY ? d.debtId() : 0)));
            }
            case DebtSegmentStarted e -> {
                DebtState d = g.debt();
                FlowFrame top = g.flow().top().orElse(null);
                check(d != null && d.path() == DebtPath.MANUAL && d.debtId() == e.debtId() && e.segment() == d.segment() + 1 && e.segment() <= 2
                        && top != null && top.windowId() == e.windowId() && top.kind() == FlowKind.DEBT
                        && d.debtor().equals(top.owner()), "debt segment mismatch");
                yield g.withDebt(d.segment(e.segment(), e.windowId()));
            }
            case DebtContinued e -> {
                DebtState d = g.debt();
                check(d != null && d.path() == DebtPath.MANUAL && d.debtId() == e.debtId() && d.segment() == 2 && !d.continued(), "debt continue mismatch");
                yield g.withDebt(d.continuedNow());
            }
            case DebtPaid e -> {
                DebtState d = g.debt();
                check(d != null && d.path() == DebtPath.MANUAL && d.debtId() == e.debtId() && d.amount() == e.amount() && d.segment() >= 1
                        && g.ledger().available(d.debtor()) >= e.amount(), "debt payment mismatch");
                String to = d.creditor() == null ? Ledger.SYSTEM : d.creditor();
                Ledger ledger = g.ledger().transfer(d.debtor(), to, e.amount(), DEBT_PAYMENT, "debt-" + d.debtId());
                GameState paid = g.withLedger(ledger).withDebt(null);
                yield paid.withTurn(t.withLanding(LandingRules.consume(rules, paid, l, LandingResult.PAID)));
            }
            case LiquidationStarted e -> {
                PlayerState p = g.player(e.playerId()).orElse(null);
                boolean ok = p != null && p.alive() && k.liquidating() == null;
                if (e.debtId() != 0) {
                    ok &= g.debt() != null && g.debt().debtId() == e.debtId() && g.debt().debtor().equals(e.playerId())
                            && e.outcome() == LifeState.BANKRUPT;
                    DebtState d = g.debt();
                    ok &= d != null && k.bankruptcyDebtId() == e.debtId() && g.flow().frames().isEmpty()
                            && (d.path() == DebtPath.DIRECT_BANKRUPTCY ? d.segment() == 0 && d.windowId() == 0
                                : d.path() == DebtPath.MANUAL && (d.segment() == 1 || d.segment() == 2) && d.windowId() != 0);
                } else {
                    ok &= e.outcome() == LifeState.SURRENDERED
                            && (g.debt() == null || !e.playerId().equals(g.debt().debtor()));
                }
                // 已接受的延后认输只能在认输批次中清算（E2）
                ok &= !g.pendingSurrenders().contains(e.playerId()) || k.openBatch() != 0;
                check(ok, "liquidation start mismatch");
                yield g.withTurn(t.withTrack(k.bankruptcy(0).liquidating(new Liquidation(e.playerId(), e.outcome(), e.debtId()))));
            }
            case PlayerEliminated e -> {
                PlayerState p = g.player(e.playerId()).orElseThrow();
                Elimination x = e.elimination();
                long batch = k.openBatch() != 0 ? k.openBatch() : maxBatch(g) + 1;
                check(liquidating(k, e.playerId()) && p.alive() && e.life() == k.liquidating().outcome() && x != null
                        && x.seq() == maxSeq(g) + 1 && x.batch() == batch && x.netWorthBefore() == netWorth(rules, g, e.playerId())
                        && (k.openBatch() == 0 || e.life() == LifeState.SURRENDERED), "elimination mismatch");
                yield g.withPlayer(p.eliminated(e.life(), x))
                        .withPendingSurrenders(g.pendingSurrenders().stream().filter(id -> !id.equals(e.playerId())).toList());
            }
            case AssetLiquidated e -> {
                OwnableState o = g.board().ownable(e.tile()).orElse(null);
                PlayerState p = g.player(e.playerId()).orElseThrow();
                check(liquidating(k, e.playerId()) && k.liquidating().outcome() == LifeState.BANKRUPT
                        && p.life() == LifeState.BANKRUPT && o != null
                        && e.playerId().equals(o.owner()) && !o.mortgaged()
                        && e.proceeds() == liquidationValue(rules, board.tiles().get(e.tile())), "liquidation mismatch");
                Ledger ledger = g.ledger().transfer(Ledger.SYSTEM, e.playerId(), e.proceeds(), LIQUIDATION, "tile-" + e.tile());
                yield g.withLedger(ledger).withBoard(g.board().with(OwnableState.unowned(e.tile())));
            }
            case AssetReclaimed e -> {
                OwnableState o = g.board().ownable(e.tile()).orElse(null);
                PlayerState p = g.player(e.playerId()).orElseThrow();
                check(liquidating(k, e.playerId()) && o != null && e.playerId().equals(o.owner()) && p.life() == k.liquidating().outcome()
                        && (o.mortgaged() || k.liquidating().outcome() == LifeState.SURRENDERED), "reclaim mismatch");
                yield g.withBoard(g.board().with(OwnableState.unowned(e.tile())));
            }
            case DebtSettled e -> {
                DebtState d = g.debt();
                check(d != null && d.debtId() == e.debtId() && liquidating(k, d.debtor()) && k.liquidating().debtId() == d.debtId()
                        && java.util.Objects.equals(d.creditor(), e.creditor()) && g.board().ownedBy(d.debtor()).isEmpty()
                        && e.paid() == Math.min(g.ledger().cash(d.debtor()), d.amount()), "debt settlement mismatch");
                Ledger ledger = e.paid() == 0 ? g.ledger() : g.ledger().transfer(d.debtor(),
                        d.creditor() == null ? Ledger.SYSTEM : d.creditor(), e.paid(), DEBT_SETTLEMENT, "debt-" + d.debtId());
                GameState settled = g.withLedger(ledger).withDebt(null);
                yield settled.withTurn(t.withLanding(LandingRules.consume(rules, settled, l, LandingResult.PAID)));
            }
            case CashReclaimed e -> {
                check(liquidating(k, e.playerId()) && g.board().ownedBy(e.playerId()).isEmpty()
                        && (g.debt() == null || !g.debt().debtor().equals(e.playerId()))
                        && !g.player(e.playerId()).orElseThrow().alive() && e.amount() == g.ledger().cash(e.playerId()),
                        "cash reclaim mismatch");
                Ledger ledger = e.amount() == 0 ? g.ledger()
                        : g.ledger().transfer(e.playerId(), Ledger.SYSTEM, e.amount(), RECLAIM, "player-" + e.playerId());
                yield g.withLedger(ledger).withTurn(t.withTrack(k.liquidating(null)));
            }
            case SurrenderDeferred e -> {
                // 欠款中的债务人认输等同确认破产，不能延后
                check(g.player(e.playerId()).map(PlayerState::alive).orElse(false) && !g.pendingSurrenders().contains(e.playerId())
                        && involvedInRunningFlow(g, e.playerId()) && (g.debt() == null || !g.debt().debtor().equals(e.playerId())),
                        "surrender cannot be deferred");
                yield g.withPendingSurrenders(Immutable.append(g.pendingSurrenders(), e.playerId()));
            }
            case SurrenderBatchStarted e -> {
                // N1：批次只能在流程全部结束（无债务、无覆盖窗口）后开始
                check(k.openBatch() == 0 && !g.pendingSurrenders().isEmpty() && e.batch() == maxBatch(g) + 1 && !flowsRunning(g),
                        "surrender batch start mismatch (flows still running: " + flowsRunning(g) + ")");
                yield g.withTurn(t.withTrack(k.batch(e.batch())));
            }
            case SurrenderBatchEnded e -> {
                check(k.openBatch() == e.batch() && g.pendingSurrenders().isEmpty() && k.liquidating() == null,
                        "surrender batch end mismatch");
                yield g.withTurn(t.withTrack(k.batch(0)));
            }
            default -> throw new IllegalStateException("not an economy event: " + event);
        };
    }

    private static boolean liquidating(TurnTrack k, String playerId) {
        return k.liquidating() != null && k.liquidating().playerId().equals(playerId);
    }

    // ================================================================ 校验

    /** 经济不变量（opus-analysis 4.9 的 1、3、4、6）与落点 / 债务 / 延后认输的绑定。 */
    static void validate(EngineState engine, GameState g, RuleConfig config, boolean full) {
        BoardTemplate board = LobbyModule.board(config, g.settings());
        List<Integer> expected = new ArrayList<>();
        board.tiles().stream().filter(x -> x.type() == TileType.PROPERTY || x.type() == TileType.STATION)
                .forEach(x -> expected.add(x.index()));
        expect(g.board().ownables() != null
                && g.board().ownables().stream().map(OwnableState::tile).toList().equals(expected),
                "ownables must be exactly the property and station tiles in order");
        Pricing pricing = new Pricing(config);
        for (OwnableState o : g.board().ownables()) {
            Tile tile = board.tiles().get(o.tile());
            expect(o.lockedBy() == null || AuctionModule.locks(g, o) || TradeModule.locks(g, o),
                    "assets can only be locked by a running card auction or trade");
            expect(o.level() >= 0 && o.level() <= config.economy().maxLevel()
                    && (tile.type() == TileType.PROPERTY || o.level() == 0), "level out of range at " + o.tile());
            if (o.owner() == null) {
                expect(o.level() == 0 && !o.mortgaged() && o.principal() == 0, "an unowned asset has no level or mortgage");
            } else {
                expect(g.player(o.owner()).map(PlayerState::alive).orElse(false), "owner of " + o.tile() + " must be alive");
            }
            if (o.mortgaged()) {
                long bank = pricing.bankMortgage(basePrice(config, tile));
                expect(o.principal() == bank || o.principal() == emergencyValue(config, tile),
                        "mortgage principal of " + o.tile() + " must be a bank or emergency amount");
            } else {
                expect(o.principal() == 0, "an unmortgaged asset has no principal");
            }
        }
        // E4：冻结余额必须等于有效占用记录之和；M2 没有任何占用流程，因此全部为 0（M5 起由拍卖 / 交易占用填充）
        for (PlayerState p : g.players()) {
            expect(g.ledger().frozen(p.playerId()) == heldAmount(g, p.playerId()),
                    "frozen cash of " + p.playerId() + " must equal the amount held by running flows");
        }
        java.util.TreeSet<Long> seqs = new java.util.TreeSet<>();
        for (PlayerState p : g.players()) {
            if (p.alive()) {
                expect(p.elimination() == null, "an alive player has no elimination record");
            } else {
                expect(p.elimination() != null && p.elimination().seq() >= 1 && seqs.add(p.elimination().seq())
                        && p.elimination().batch() >= 1 && p.elimination().batch() <= p.elimination().seq(),
                        "elimination record invalid for " + p.playerId());
                expect(g.ledger().cash(p.playerId()) == 0 && g.ledger().frozen(p.playerId()) == 0 && p.hand().isEmpty()
                        && g.board().ownedBy(p.playerId()).isEmpty() && !p.inJail(),
                        "an eliminated player holds nothing: " + p.playerId());
            }
        }
        expect(seqs.isEmpty() || seqs.last() == seqs.size(), "elimination numbers must be 1..n");
        TurnState t = g.turn();
        LandingState l = t.landing();
        if (l != null) {
            expect(LandingRules.valid(l) && l.step() != null && l.landingId() >= 1 && l.landingId() == t.lastLandingId()
                    && t.chain() != null && l.chainId() == t.chain().chainId() && l.cursor() < l.tasks().size() && l.next() == null
                    && l.decisionOpen() == !l.step().awaitsFlow()
                    && (l.step() == LandingStep.DEBT || l.currentTask() == l.step())
                    && (l.step() == LandingStep.DEBT || l.pendingPayment() == 0)
                    && g.player(t.currentPlayer()).orElseThrow().position() == l.tile(), "landing invalid");
            expect(LandingRules.resting(config, g, l), l.step().name().toLowerCase(java.util.Locale.ROOT) + " step invalid");
            expect(EventModule.validResolution(config, l), "event resolution invalid");
            if (l.step() == LandingStep.DISCARD) {
                expect(g.player(t.currentPlayer()).orElseThrow().hand().get(l.event().newCardIndex())
                        == CardDeck.pick(config, l.event().cardDraw()), "new card differs from its audited receipt");
            }
        } else {
            expect(!(t.continuation() instanceof Continuation.ResumeLanding), "resume-landing continuation without a landing");
        }
        DebtState d = g.debt();
        boolean debtFrame = g.flow().frames().stream().anyMatch(f -> f.kind() == FlowKind.DEBT);
        if (d != null) {
            FlowFrame top = g.flow().top().orElse(null);
            expect(d.debtor().equals(t.currentPlayer()) && g.player(d.debtor()).orElseThrow().alive()
                    && (d.creditor() == null || g.player(d.creditor()).map(PlayerState::alive).orElse(false))
                    && d.debtId() == t.lastDebtId() && d.debtId() >= 1 && d.path() == DebtPath.MANUAL
                    && d.source() != null && FeeRules.rule(d.source().kind()).cause().equals(d.cause())
                    && d.source().amount() == d.amount() && java.util.Objects.equals(d.source().creditor(), d.creditor())
                    && FeeRules.valid(config, g, l, d.source())
                    && d.amount() > 0 && g.ledger().available(d.debtor()) < d.amount()
                    && (d.segment() == 1 || d.segment() == 2) && (!d.continued() || d.segment() == 2)
                    && top != null && top.kind() == FlowKind.DEBT && top.windowId() == d.windowId()
                    && d.debtor().equals(top.owner()) && l != null && l.step() == LandingStep.DEBT, "debt invalid");
        } else {
            expect(!debtFrame, "a debt window without a debt");
        }
        java.util.TreeSet<String> pending = new java.util.TreeSet<>();
        for (String id : g.pendingSurrenders()) {
            expect(id != null && pending.add(id) && g.player(id).map(PlayerState::alive).orElse(false),
                    "pending surrenders must be distinct alive players");
        }
        expect(g.pendingSurrenders().isEmpty() || d != null
                || g.flow().frames().stream().anyMatch(f -> f.kind() != FlowKind.TURN),
                "deferred surrenders need a running flow");
        // P7：恢复与重建入口完整重放；每步边界在可信前缀之上增量校验
        if (full) {
            TrustedLedgers.verifyFully(g.ledger());
        } else {
            TrustedLedgers.verify(g.ledger());
        }
    }

    /**
     * 玩家被进行中的流程占用的金额（E4 接口占位）。M5 实现必须<b>从独立的占用记录</b>（拍卖最高报价等，归属到具体流程）求和，
     * 并核对资产锁 {@code OwnableState.lockedBy} 的流程归属；<b>不得直接返回 {@code ledger.frozen()} 自证</b>。
     * M2 没有占用流程，恒为 0。
     */
    static long heldAmount(GameState g, String playerId) {
        return AuctionModule.held(g, playerId);
    }

    // ================================================================ helpers

    private static GameState game(DecisionContext<SessionState> ctx) {
        return ctx.state().game();
    }

    private static BoardTemplate board(DecisionContext<SessionState> ctx) {
        return LobbyModule.board(ctx.config(), game(ctx).settings());
    }

    private static boolean current(GameState g, String playerId) {
        return playerId != null && playerId.equals(g.turn().currentPlayer());
    }

    private static void check(boolean condition, String message) {
        LobbyModule.check(condition, message);
    }

    private static void expect(boolean condition, String message) {
        LobbyModule.expect(condition, message);
    }
}
