package com.millionnaire.engine.config;

import java.util.ArrayList;
import java.util.List;

/** Presentation and social controls bound to the room's published rule version. */
public record ExperienceConfig(boolean turnReminderEnabled, boolean turnSoundEnabled, boolean hapticAllowed,
        int reminderHighlightMs, boolean cardPreviewEnabled, boolean practiceEnabled,
        boolean emotesEnabled, int emoteCooldownMs, int emoteVisibleMs, boolean returnSummaryEnabled,
        int summaryMaxLines) {
    public static final ExperienceConfig DEFAULT = new ExperienceConfig(true, true, true, 2400, true, true,
            true, 3000, 2400, true, 8);
    public static List<String> validate(ExperienceConfig c) {
        var errors = new ArrayList<String>();
        if (c == null) { errors.add("体验设置不能为空"); return errors; }
        if (c.reminderHighlightMs < 500 || c.reminderHighlightMs > 10000) errors.add("回合高亮须为500～10000毫秒");
        if (c.emoteCooldownMs < 1000 || c.emoteCooldownMs > 60000) errors.add("表情间隔须为1000～60000毫秒");
        if (c.emoteVisibleMs < 500 || c.emoteVisibleMs > 10000) errors.add("表情展示须为500～10000毫秒");
        if (c.summaryMaxLines < 1 || c.summaryMaxLines > 20) errors.add("返回摘要须显示1～20条");
        return errors;
    }
}
