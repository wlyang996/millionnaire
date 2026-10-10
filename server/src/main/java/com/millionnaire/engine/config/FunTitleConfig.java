package com.millionnaire.engine.config;

import com.millionnaire.engine.serialize.Immutable;
import java.util.List;

public record FunTitleConfig(boolean enabled, List<Title> titles) {
    public enum Kind { RENT_KING, PROPERTY_TYCOON, LUCKY_STAR, COMEBACK }
    public record Title(Kind kind, boolean enabled, String name, long minimum) { }
    public static final FunTitleConfig DEFAULT = new FunTitleConfig(true, List.of(
            new Title(Kind.RENT_KING, true, "收租王", 1), new Title(Kind.PROPERTY_TYCOON, true, "地产大亨", 1),
            new Title(Kind.LUCKY_STAR, true, "幸运之星", 1), new Title(Kind.COMEBACK, true, "逆风翻盘", 2)));
    public static final FunTitleConfig NONE = new FunTitleConfig(false, DEFAULT.titles());
    public FunTitleConfig { titles = Immutable.list(titles == null ? List.of() : titles); }
}
