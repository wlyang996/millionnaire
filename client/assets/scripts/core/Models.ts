/**
 * 数据模型：字段名与服务端 SessionView / GameView / RoomSettings 对齐（Java record -> TS interface）。
 * 标注 [公开] 所有人可见；[私有] 仅本人；[待服务端] 服务端 M1 视图尚未提供、本客户端为演示预设的字段。
 */

export type ConnState = 'ONLINE' | 'SUSPECT' | 'OFFLINE';
export type ControlMode = 'MANUAL' | 'AWAY' | 'HOSTED';
export type LifeState = 'ALIVE' | 'BANKRUPT' | 'SURRENDERED';
export type RoomStatus = 'LOBBY' | 'PLAYING' | 'SETTLING';
export type GamePhase = 'ORDERING' | 'PLAYING' | 'FINISHED';
export type EndMode = 'TIME_LIMIT' | 'BANKRUPTCY';
export type TurnStage = 'NONE' | 'JAIL_DECISION' | 'PRE_ROLL' | 'LANDING' | 'AWAITING_FLOW';
export type FlowKind = 'TURN' | 'AUCTION' | 'TRADE' | 'ATTACK' | 'DEBT' | 'MINIGAME' | 'DISCARD' | 'RESPONSE';
export type TileType = 'START' | 'PROPERTY' | 'STATION' | 'EVENT' | 'BANK' | 'JAIL' | 'REST' | 'GAME_ZONE';
export type Tier = 'LOW' | 'MID' | 'HIGH';

/** 14 种道具（与服务端 CardType 一致） */
export type CardType =
    | 'ROADBLOCK' | 'RENT_WAIVER' | 'BUILD' | 'DOWNGRADE' | 'FIXED_MOVE' | 'JAIL_RELEASE' | 'AUCTION'
    | 'TRADE' | 'REFUSE_PURCHASE' | 'HOUSE_PROTECTION' | 'QUERY' | 'FORCED_PURCHASE' | 'DEMOLISH' | 'CLEAR_LAND';

/** 房主可改设置（RoomSettings）。boardId 取 'classic-30' / 'classic-50'。 */
export interface RoomSettings {
    boardId: 'classic-30' | 'classic-50';
    initialCash: number; // 2000 | 3000 | 5000
    endMode: EndMode;
    timeLimitMinutes: number; // 15 | 30 | 60
    rollSeconds: number; // 15 | 30 | 45 | 60
}

/** 房间成员（Member）。avatar / speaking 为客户端展示字段 [待服务端]。 */
export interface Member {
    playerId: string;
    nickname: string;
    ready: boolean;
    avatar: number;
    speaking?: boolean;
}

/** [公开] 对局玩家（GameView.PublicPlayer，顺序即行动顺序） */
export interface PlayerView {
    playerId: string;
    nickname: string; // 来自 Member
    avatar: number; // 来自 Member，头像编号
    position: number;
    cash: number;
    handCount: number;
    life: LifeState;
    inJail: boolean;
    jailFailures: number;
    control: ControlMode;
    conn: ConnState;
    /** [待服务端] 拍卖冻结资金 */
    frozen: number;
}

/** 棋盘格（模板静态数据）。[待服务端] 现 BoardState 仅含 boardId，格子表由配置下发。 */
export interface BoardTile {
    index: number;
    type: TileType;
    name: string;
    tier?: Tier; // 仅 PROPERTY
    /** 指定拍卖地产（30 格 3 块、50 格 5 块） */
    auctionLot?: boolean;
}

/** [公开][待服务端] 地产/车站动态状态 */
export interface PropertyState {
    tileIndex: number;
    owner: string | null;
    level: number; // 0..3，车站恒为 0
    mortgaged: boolean;
    /** 抵押时实得金额（赎回本金）；未抵押为 0 */
    mortgagePaid: number;
    /** 当前保留的累计升级费 */
    upgradeSpent: number;
}

/** 道具牌。[私有] 手牌内容仅本人可见，他人只看 handCount */
export interface Card {
    id: string;
    type: CardType;
}

/** [公开] 窗口（GameView.OpenWindow） */
export interface OpenWindow {
    windowId: number;
    kind: FlowKind;
    owner: string;
    opensAt: number;
    deadline: number; // 演示里为 Clock 虚拟毫秒
    paused: boolean;
}

export interface OrderDraw {
    playerId: string;
    value: number;
}

export interface ChatLine {
    from: string;
    text: string;
}

/** 对局视图（GameView）；myHand 为 [私有]；tiles/properties/lastDice/chat 为 [待服务端]。 */
export interface GameView {
    gameNo: number;
    phase: GamePhase;
    players: PlayerView[];
    orderDraws: OrderDraw[];
    boardId: string;
    turnNo: number;
    currentPlayer: string;
    stage: TurnStage;
    globalEndsAt: number;
    windows: OpenWindow[];
    myHand: Card[]; // [私有]
    tiles: BoardTile[];
    properties: PropertyState[];
    lastDice: number;
    chat: ChatLine[];
    /** [联机] 当前落点（服务端 PublicLanding）；演示模式不设 */
    landing?: LandingInfo | null;
    /** [联机] 进行中的债务（服务端 PublicDebt）；演示模式不设 */
    debt?: DebtInfo | null;
}

/** [联机] 当前落点：格号、等待的步骤（如 BUY / UPGRADE）、决策是否仍待做 */
export interface LandingInfo {
    landingId: number;
    tile: number;
    step: string;
    decisionPending: boolean;
}

/** [联机] 进行中的债务 */
export interface DebtInfo {
    debtId: number;
    debtor: string;
    creditor: string | null;
    amount: number;
    segment: number;
    continued: boolean;
    continueAvailable: boolean;
    windowId: number;
}

/** 结算名次（Standing）。并列按 1/1/3 编号。 */
export interface Standing {
    playerId: string;
    rank: number;
    netWorth: number;
    cash: number;
    bankrupt?: boolean;
    /** 展示用 */
    nickname?: string;
    avatar?: number;
}

export interface GameResult {
    gameNo: number;
    reason: string;
    standings: Standing[];
}

/** 会话视图（SessionView） */
export interface SessionView {
    roomId: string;
    status: RoomStatus;
    hostId: string;
    members: Member[];
    settings: RoomSettings;
    game: GameView | null;
    gamesPlayed: number;
    lastResult: GameResult | null;
}

/** 战绩（最近 20 局，只看自己） */
export interface HistoryEntry {
    mode: string;
    players: number;
    minutes: number;
    rank: number;
    finalAssets: number;
    /** 结束时间（epoch 毫秒）；演示数据没有 */
    endedAt?: number;
}

/** 本机资料 */
export interface Profile {
    nickname: string;
    avatar: number;
    loggedIn: boolean;
}

/** 拍卖状态（演示用）。[待服务端] */
export interface AuctionView {
    tileIndex: number;
    base: number; // 基准价值
    start: number;
    cap: number;
    minRaise: number;
    highBid: number;
    highBidder: string | null;
    myFrozen: number;
    seller: string | null;
}

/** 欠款状态（演示用）。[待服务端] */
export interface DebtView {
    debtor: string;
    amount: number;
    creditor: string | null; // null = 银行/系统
    segment: 1 | 2;
    /** 已应急抵押的 tileIndex */
    selected: number[];
}

export const CARD_NAMES: Record<CardType, string> = {
    ROADBLOCK: '路障', RENT_WAIVER: '免租', BUILD: '建造', DOWNGRADE: '降级', FIXED_MOVE: '定点移动',
    JAIL_RELEASE: '出狱', AUCTION: '拍卖', TRADE: '交易', REFUSE_PURCHASE: '拒绝购买', HOUSE_PROTECTION: '房屋保护',
    QUERY: '查询', FORCED_PURCHASE: '强制购房', DEMOLISH: '拆楼', CLEAR_LAND: '清地',
};

export const CARD_ORDER: CardType[] = [
    'ROADBLOCK', 'RENT_WAIVER', 'BUILD', 'DOWNGRADE', 'FIXED_MOVE', 'JAIL_RELEASE', 'AUCTION',
    'TRADE', 'REFUSE_PURCHASE', 'HOUSE_PROTECTION', 'QUERY', 'FORCED_PURCHASE', 'DEMOLISH', 'CLEAR_LAND',
];

export const CARD_DESC: Record<CardType, string> = {
    ROADBLOCK: '在当前位置放置，下一名经过的其他玩家被迫停下',
    RENT_WAIVER: '免除本次租金（响应窗使用）',
    BUILD: '当前位置是自己的地产时免费升一级',
    DOWNGRADE: '当前位置他人未抵押地产降一级',
    FIXED_MOVE: '投骰前选择前进 1～6 格，替代投骰',
    JAIL_RELEASE: '自己的回合释放出狱，再正常移动',
    AUCTION: '拍卖自己任意一处未抵押资产',
    TRADE: '卖给指定买家，价格在标准价值 50%～2.5 倍',
    REFUSE_PURCHASE: '抵挡一次强制购房',
    HOUSE_PROTECTION: '抵挡一次降级、拆楼或清地',
    QUERY: '查看指定玩家的卡牌，只对你显示',
    FORCED_PURCHASE: '以标准价值 1.5 倍购买当前他人资产',
    DEMOLISH: '清除当前位置他人地产的全部升级',
    CLEAR_LAND: '清除当前位置他人地产的等级与所有权',
};

export const CONN_LABEL: Record<ConnState, string> = { ONLINE: '在线', SUSPECT: '疑似断线', OFFLINE: '已掉线' };
