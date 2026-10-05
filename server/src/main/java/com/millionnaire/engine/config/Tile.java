package com.millionnaire.engine.config;

/** 棋盘格；tier 仅普通地产非空，auctionDesignated 仅普通地产可为 true。 */
public record Tile(int index, TileType type, Tier tier, boolean auctionDesignated) {
}
