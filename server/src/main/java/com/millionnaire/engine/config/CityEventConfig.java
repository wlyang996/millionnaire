package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

public record CityEventConfig(boolean enabled, List<EndMode> allowedModes, int firstCheckRound,
                              int checkEveryRounds, int triggerPercent, int advanceRounds,
                              int announcementMs, int activationMs, int endMs, List<Spec> pool) {
    public enum Kind { UPGRADE_DISCOUNT, STATION_RENT, PROPERTY_RENT }
    public record Spec(Kind kind, boolean enabled, String name, int weight, int multiplierPercent,
                       int durationRounds, String artworkKey) { }
    public static final CityEventConfig DEFAULT = new CityEventConfig(true, List.of(EndMode.values()), 5, 5, 30, 1,
            3000, 1500, 1500, List.of(
                    new Spec(Kind.UPGRADE_DISCOUNT, true, "建设节", 1, 80, 2, "city_construction"),
                    new Spec(Kind.STATION_RENT, true, "旅游旺季", 1, 150, 2, "scene_station"),
                    new Spec(Kind.PROPERTY_RENT, true, "安居日", 1, 80, 2, "scene_property_low")));
    public static final CityEventConfig NONE = new CityEventConfig(false, DEFAULT.allowedModes(), 5, 5, 30, 1,
            3000, 1500, 1500, DEFAULT.pool());
    public CityEventConfig {
        allowedModes = Immutable.list(allowedModes == null ? List.of() : allowedModes);
        pool = Immutable.list(pool == null ? List.of() : pool);
    }
    public List<Spec> enabledPool() { return pool.stream().filter(s -> s.enabled() && s.weight() > 0).toList(); }
    public int totalWeight() { return enabledPool().stream().mapToInt(Spec::weight).sum(); }
    public Spec pick(int value) {
        for (Spec s : enabledPool()) { if (value < s.weight()) return s; value -= s.weight(); }
        throw new IllegalArgumentException("city event draw outside pool");
    }
}
