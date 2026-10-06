package com.millionnaire.engine.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.millionnaire.engine.config.BoardTemplate;
import com.millionnaire.engine.config.ConfigValidator;
import com.millionnaire.engine.config.RuleConfig;
import com.millionnaire.engine.config.RuleConfigs;
import com.millionnaire.engine.config.RulesV1Spec;
import com.millionnaire.engine.config.TileType;
import org.junit.jupiter.api.Test;

/** 正式布局（美术设计稿 board-v6）：合规，且四角等关键格与设计稿一致。场景测试用 TestBoards 的固定棋盘。 */
class DesignBoardTest {
    private final RuleConfig config = RuleConfigs.defaultV1();

    private BoardTemplate board(String id) {
        return config.board(id).orElseThrow();
    }

    @Test
    void productionConfigPassesAllValidation() {
        ConfigValidator.validateOrThrow(config);
        assertTrue(RulesV1Spec.validate(config).isEmpty(), RulesV1Spec.validate(config).toString());
    }

    @Test
    void cornersAndSpecialTilesMatchTheDesign() {
        BoardTemplate b30 = board(RuleConfigs.BOARD_30); // 8 列 × 9 行环：角为 0、7、15、22
        assertEquals(TileType.START, b30.tiles().get(0).type());
        assertEquals(TileType.JAIL, b30.tiles().get(7).type());
        assertEquals(TileType.GAME_ZONE, b30.tiles().get(15).type());
        assertEquals(TileType.REST, b30.tiles().get(22).type());
        assertEquals(7, TurnModule.jailIndex(b30));

        BoardTemplate b50 = board(RuleConfigs.BOARD_50);
        assertEquals(TileType.START, b50.tiles().get(0).type());
        assertEquals(TileType.JAIL, b50.tiles().get(11).type());
        assertEquals(11, TurnModule.jailIndex(b50));
        assertEquals(TileType.GAME_ZONE, b50.tiles().get(25).type());
        assertEquals(TileType.REST, b50.tiles().get(36).type());
    }

    @Test
    void auctionLotsPerTier() {
        assertEquals(3, board(RuleConfigs.BOARD_30).tiles().stream().filter(t -> t.auctionDesignated()).count());
        assertEquals(5, board(RuleConfigs.BOARD_50).tiles().stream().filter(t -> t.auctionDesignated()).count());
    }
}
