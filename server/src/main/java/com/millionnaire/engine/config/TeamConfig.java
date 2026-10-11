package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.ArrayList;
import java.util.List;

/** Personal accounts and ownership; team rules are room-bound, never read from a live admin draft. */
public record TeamConfig(boolean enabled, List<Integer> teamSizeOptions, int defaultTeamSize,
                         boolean allowBots, boolean allyRentWaiver, boolean allyRoadblockPass,
                         boolean aidEnabled, long aidMaxAmount, int aidPerTurn,
                         List<Long> aidQuickAmounts, boolean debtAidEnabled, long debtAidWindowMs) {
    /** Opt-in only: old snapshots and ordinary rooms never activate teams implicitly. */
    public static final TeamConfig DEFAULT = new TeamConfig(false, List.of(2, 3, 4), 2,
            true, true, true, true, 5000, 1, List.of(100L, 300L, 500L), true, 20_000);
    public static final TeamConfig NONE = DEFAULT;

    public TeamConfig {
        teamSizeOptions = Immutable.list(teamSizeOptions);
        aidQuickAmounts = Immutable.list(aidQuickAmounts);
    }

    public static List<String> validate(TeamConfig c) {
        if (c == null) return List.of("组队参数缺失");
        var errors = new ArrayList<String>();
        if (c.teamSizeOptions == null || c.teamSizeOptions.isEmpty() || c.teamSizeOptions.size() > 3
                || c.teamSizeOptions.stream().anyMatch(n -> n == null || n < 2 || n > 4)
                || c.teamSizeOptions.stream().distinct().count() != c.teamSizeOptions.size()
                || !c.teamSizeOptions.contains(c.defaultTeamSize))
            errors.add("组队形式须选2v2、3v3或4v4且不重复，默认形式须在选项内");
        if (c.aidMaxAmount < 1 || c.aidMaxAmount > 1_000_000)
            errors.add("援助单笔上限须为1～1000000");
        if (c.aidPerTurn < 1 || c.aidPerTurn > 10)
            errors.add("主动援助每回合次数须为1～10");
        if (c.aidQuickAmounts == null || c.aidQuickAmounts.isEmpty() || c.aidQuickAmounts.size() > 6
                || c.aidQuickAmounts.stream().anyMatch(n -> n == null || n < 1 || n > c.aidMaxAmount)
                || c.aidQuickAmounts.stream().distinct().count() != c.aidQuickAmounts.size())
            errors.add("援助快捷金额须配置1～6项，不重复且每项不超过单笔上限");
        if (c.debtAidWindowMs < 1000 || c.debtAidWindowMs > 120_000)
            errors.add("欠款援助等待时长须为1000～120000毫秒");
        return List.copyOf(errors);
    }
}
