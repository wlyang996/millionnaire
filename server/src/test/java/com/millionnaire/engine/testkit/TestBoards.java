package com.millionnaire.engine.testkit;

import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;

/**
 * 场景测试用的固定棋盘：rules-v1 早期布局（同样满足 RulesV1Spec 的数量与拍卖地约束）。
 * 场景测试按格号编排骰点，与正式布局（美术设计稿，见 RuleConfigs.LAYOUT_30/50）解耦；正式布局另由 DesignBoardTest 校验。
 */
public final class TestBoards {
    public static final String LEGACY_30 =
            "S L E L T M L* M J L E B T M* H R L E M T H E M G L H* E T M H";
    public static final String LEGACY_50 =
            "S L M E L M T L H E B M L* J H T M* L E G H E T M L R H M E L "
            + "H* T M E L* B H M G L E T H M* E L H T E M";

    private TestBoards() {
    }

    public static RuleConfig legacyV1() {
        return RuleConfigs.v1(LEGACY_30, LEGACY_50);
    }
}
