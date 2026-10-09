package com.millionnaire.engine.config;

/**
 * 起点三选一（2026-10-08，管理后台可配）：停在起点（正常走到或被事件送到）时，面前三张背面卡选一张，
 * 按 cashWeight : cardWeight 的比例得到现金（cashMin～cashMax 按 cashStep 等概率）或一张随机道具（与事件得道具同概率）。
 * 两个权重都为 0 即关闭。
 */
public record StartPick(int cashWeight, int cardWeight, long cashMin, long cashMax, long cashStep) {
    public static final StartPick NONE = new StartPick(0, 0, 100, 100, 100);
    public static final StartPick DEFAULT = new StartPick(60, 40, 200, 1000, 100);
    /** 面前的卡数（只影响界面与命令里的序号范围）。 */
    public static final int CHOICES = 3;

    public boolean enabled() {
        return cashWeight + cardWeight > 0;
    }

    public int cashBound() {
        return Math.toIntExact((cashMax - cashMin) / cashStep + 1);
    }
}
