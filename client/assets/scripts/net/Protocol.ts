/**
 * 联机协议的服务端数据形状（与 gateway/README.md 一致）。纯 TS，不依赖 cc。
 * 服务端视图（S 前缀）经 ViewAdapter 转成 core/Models 的客户端模型后再给界面用。
 */

export interface SMember {
    playerId: string;
    nickname: string;
    ready: boolean;
}

export interface SSettings {
    boardId: string;
    initialCash: number;
    endMode: 'TIME_LIMIT' | 'BANKRUPTCY';
    timeLimitMinutes: number;
    rollSeconds: number;
}

export interface SPlayer {
    playerId: string;
    position: number;
    cash: number;
    frozen: number;
    handCount: number;
    life: 'ALIVE' | 'BANKRUPT' | 'SURRENDERED';
    inJail: boolean;
    jailFailures: number;
    control: 'MANUAL' | 'AWAY' | 'HOSTED';
    conn: 'ONLINE' | 'SUSPECT' | 'OFFLINE';
}

export interface SOwnable {
    tile: number;
    owner: string | null;
    level: number;
    mortgaged: boolean;
    principal: number;
    lockedBy: string | null;
}

export interface SWindow {
    windowId: number;
    kind: string;
    owner: string;
    opensAt: number;
    deadline: number;
    paused: boolean;
}

export interface SLanding {
    landingId: number;
    tile: number;
    step: string;
    decisionPending: boolean;
}

export interface SDebt {
    debtId: number;
    debtor: string;
    creditor: string | null;
    amount: number;
    segment: number;
    continued: boolean;
    continueAvailable: boolean;
    windowId: number;
}

export interface SMinigame {
    minigameId: number;
    trigger: string;
    participants: string[];
    teeth: number;
    picks: number[];
    picker: string;
    windowId: number;
}

export interface SCards {
    chanceUsed: string[];
    response: {
        attacker: string; owner: string; tile: number; attack: string; response: string; windowId: number;
    } | null;
}

export interface SGame {
    gameNo: number;
    phase: 'RUNNING' | 'DRAINING';
    players: SPlayer[];
    orderDraws: { playerId: string; draws: number[] }[];
    board: { boardId: string; ownables: SOwnable[]; roadblocks?: { id: number; tile: number; owner: string }[] };
    turnNo: number;
    currentPlayer: string | null;
    stage: string;
    globalEndsAt: number;
    windows: SWindow[];
    landing: SLanding | null;
    debt: SDebt | null;
    myHand: string[];
    /** 进行中的虎口拔牙（旧版后台没有该字段） */
    minigame?: SMinigame | null;
    /** 道具（旧版后台没有该字段） */
    cards?: SCards | null;
    /** 交易（旧版后台没有该字段） */
    trade?: { seller: string; buyer: string; tile: number; price: number; windowId: number } | null;
    /** 拍卖（旧版后台没有该字段） */
    auction?: {
        kind: 'LAND' | 'CARD'; tile: number; seller: string | null; initiator: string | null; basis: number; start: number;
        minRaise: number; cap: number; highBid: number; highBidder: string | null; minimumBid: number; hardEnd: number; windowId: number;
    } | null;
}

export interface SStanding {
    playerId: string;
    rank: number;
    netWorth: number;
    cash: number;
}

export interface SResult {
    gameNo: number;
    reason: string;
    standings: SStanding[];
}

export interface SView {
    roomId: string;
    status: 'OPEN' | 'CLOSED';
    hostId: string | null;
    members: SMember[];
    settings: SSettings;
    game: SGame | null;
    gamesPlayed: number;
    lastResult: SResult | null;
}

/** 本步对你可见的事件：kind 为服务端事件类名（如 DiceRolled、PlayerMoved），data 为其字段。 */
export interface SEvent {
    kind: string;
    data: Record<string, unknown> | null;
}

export interface UpdateMsg {
    type: 'UPDATE';
    roomCode: string;
    version: number;
    serverTime: number;
    events: SEvent[];
    view: SView;
}

export interface ResultMsg {
    type: 'RESULT';
    requestId: string | null;
    ok: boolean;
    /** ACCEPTED / REJECTED（规则拒绝）/ DUPLICATE / STALE / ERROR（网关错误）/ OFFLINE / TIMEOUT（客户端本地） */
    outcome: string;
    code: string | null;
    roomCode?: string;
    message?: string;
    settingsCode?: string;
}

export interface HelloMsg {
    type: 'HELLO';
    userId: string;
    nickname: string;
    serverTime: number;
    roomCode: string | null;
}

/** GET /api/me/history 的一行（我的最近 20 局，新的在前）。rank 等为 null 表示中止局。 */
export interface SHistoryRow {
    gameNo: number; endMode: 'TIME_LIMIT' | 'BANKRUPTCY'; timeLimitMinutes: number | null; boardId: string;
    playerCount: number; startedAt: number; endedAt: number; endReason: string;
    rank: number | null; netWorth: number | null; cash: number | null; life: string;
}

export interface BoardTemplateTile {
    index: number;
    type: 'START' | 'PROPERTY' | 'STATION' | 'EVENT' | 'BANK' | 'JAIL' | 'REST' | 'GAME_ZONE';
    tier: 'LOW' | 'MID' | 'HIGH' | null;
    auctionDesignated: boolean;
}

export interface BoardTemplate {
    id: string;
    minPlayers: number;
    maxPlayers: number;
    tiles: BoardTemplateTile[];
}

/** 客户端可发的对局命令（GAME 消息的 command）。 */
export type GameCommandName =
    | 'RollDice' | 'PayBail' | 'DrawEventCard' | 'DiscardCard' | 'BuyProperty' | 'DeclinePurchase'
    | 'StartLandAuction' | 'UpgradeProperty' | 'SkipUpgrade' | 'BankMortgage' | 'Redeem' | 'EmergencyMortgage'
    | 'FinishBank' | 'ContinueDebt' | 'DeclareBankruptcy' | 'ResumeControl' | 'Surrender' | 'PickTooth'
    | 'UseCard' | 'RespondCard' | 'RequestAuction' | 'Bid' | 'RequestTrade' | 'AnswerTrade';

/** GAME 命令参数（数字、玩家 ID、卡种或是否使用）。 */
export type GameArgs = Record<string, number | string | boolean | null>;
