package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

/** Room-bound series rules. Historical configurations leave this feature disabled. */
public record SeriesConfig(boolean enabled, List<Integer> gameOptions, int defaultGames,
                           List<Integer> rankPoints, TieMode tieMode, boolean allowBots, int minPlayers) {
    public enum TieMode { SHARED_RANK, SPLIT_OCCUPIED }
    public static final SeriesConfig DEFAULT = new SeriesConfig(true, List.of(3, 5, 7), 3,
            List.of(10, 6, 4, 3, 2, 1, 0, 0), TieMode.SHARED_RANK, true, 2);
    public static final SeriesConfig NONE = new SeriesConfig(false, DEFAULT.gameOptions, DEFAULT.defaultGames,
            DEFAULT.rankPoints, DEFAULT.tieMode, DEFAULT.allowBots, DEFAULT.minPlayers);
    public SeriesConfig {
        gameOptions = Immutable.list(gameOptions);
        rankPoints = Immutable.list(rankPoints);
    }
    public static List<String> validate(SeriesConfig c) {
        var errors = new java.util.ArrayList<String>();
        if (c == null) return List.of("系列赛参数缺失");
        if (c.gameOptions == null || c.gameOptions.isEmpty() || c.gameOptions.size() > 19
                || c.gameOptions.stream().anyMatch(n -> n == null || n < 2 || n > 20)
                || new java.util.TreeSet<>(c.gameOptions).size() != c.gameOptions.size()
                || !c.gameOptions.contains(c.defaultGames)) errors.add("系列赛局数须为2～20、不重复，默认局数须在选项内");
        if (c.rankPoints == null || c.rankPoints.size() != 8
                || c.rankPoints.stream().anyMatch(n -> n == null || n < 0 || n > 1000)) errors.add("系列赛须配置8个名次积分，每项0～1000");
        else for (int i = 1; i < 8; i++) if (c.rankPoints.get(i) > c.rankPoints.get(i - 1)) {
            errors.add("系列赛名次积分不能随名次递增"); break;
        }
        if (c.tieMode == null) errors.add("请选择并列计分方式");
        if (c.minPlayers < 2 || c.minPlayers > 8) errors.add("系列赛最少人数须为2～8");
        return List.copyOf(errors);
    }
}
