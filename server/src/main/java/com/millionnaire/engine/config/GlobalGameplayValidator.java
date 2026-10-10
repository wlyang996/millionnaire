package com.millionnaire.engine.config;

import java.util.ArrayList;
import java.util.TreeSet;
import java.util.List;

/** Shared validation, also used by the Chinese management API. */
public final class GlobalGameplayValidator {
    private GlobalGameplayValidator() { }
    public static List<String> validate(RoundRewardConfig r, FunTitleConfig f, CityEventConfig c) {
        List<String> errors = new ArrayList<>();
        name(errors, "轮次奖励名称", r.name(), 2, 12);
        range(errors, "限时模式奖励轮次", r.timeLimitRewardRound(), 1, 1000);
        range(errors, "破产模式奖励轮次", r.bankruptcyRewardRound(), 1, 1000);
        range(errors, "收入奖励百分比", r.rewardPercent(), 0, 100);
        range(errors, "每人奖励上限", r.perPlayerCap(), 0, 1_000_000);
        if (!List.of(1, 10, 50, 100).contains(r.roundingUnit())) errors.add("奖励取整单位仅支持1、10、50、100");
        else if (r.perPlayerCap() % r.roundingUnit() != 0) errors.add("奖励上限必须是取整单位的整数倍");
        if (r.incomeSources().contains(null) || new TreeSet<>(r.incomeSources()).size() != r.incomeSources().size())
            errors.add("收入来源不能重复或为空");
        range(errors, "奖励展示毫秒", r.presentationMs(), 1000, 10000);
        TreeSet<FunTitleConfig.Kind> titles = new TreeSet<>();
        for (var t : f.titles()) {
            if (t == null || t.kind() == null || !titles.add(t.kind())) { errors.add("称号类型不能重复或为空"); continue; }
            name(errors, "称号名称", t.name(), 2, 12);
            range(errors, "称号最低指标", t.minimum(), 1, t.kind() == FunTitleConfig.Kind.COMEBACK ? 7 : 1_000_000);
        }
        if (titles.size() != FunTitleConfig.Kind.values().length) errors.add("四种称号都须配置，暂不使用可关闭");
        range(errors, "城市事件首次检查轮次", c.firstCheckRound(), 1, 1000);
        range(errors, "城市事件检查间隔", c.checkEveryRounds(), 1, 100);
        range(errors, "城市事件触发概率", c.triggerPercent(), 0, 100);
        range(errors, "城市事件提前轮数", c.advanceRounds(), 1, 10);
        range(errors, "城市事件预告毫秒", c.announcementMs(), 1000, 10000);
        range(errors, "城市事件生效提示毫秒", c.activationMs(), 0, 10000);
        range(errors, "城市事件结束提示毫秒", c.endMs(), 0, 10000);
        if (c.allowedModes().contains(null) || new TreeSet<>(c.allowedModes()).size() != c.allowedModes().size())
            errors.add("城市事件生效模式不能重复或为空");
        TreeSet<CityEventConfig.Kind> kinds = new TreeSet<>();
        for (var s : c.pool()) {
            if (s == null || s.kind() == null || !kinds.add(s.kind())) { errors.add("城市事件类型不能重复或为空"); continue; }
            name(errors, "城市事件名称", s.name(), 2, 12);
            range(errors, "城市事件权重", s.weight(), 0, 1000);
            range(errors, "城市事件倍率百分比", s.multiplierPercent(), 1, 300);
            range(errors, "城市事件持续轮数", s.durationRounds(), 1, 20);
            if (!List.of("city_construction", "scene_station", "scene_property_low").contains(s.artworkKey()))
                errors.add("城市事件插画须选择已确认素材");
        }
        return List.copyOf(errors);
    }
    private static void range(List<String> e, String name, long v, long min, long max) {
        if (v < min || v > max) e.add(name + "须在" + min + "～" + max + "之间");
    }
    private static void name(List<String> e, String name, String v, int min, int max) {
        if (v == null || v.isBlank() || v.codePointCount(0, v.length()) < min || v.codePointCount(0, v.length()) > max)
            e.add(name + "须为" + min + "～" + max + "字");
    }
}
