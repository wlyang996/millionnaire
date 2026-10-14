package com.millionnaire.gateway.room;

/** Operational metadata only: no player identifiers, cards, balances, RNG or command state. */
public record AdminRoomSnapshot(String code, String status, String boardId, String endMode,
                                int players, int spectators, int bots, int automated,
                                long round, long turn, long gameNo, long configId,
                                long characterVersion, long createdAt, long gameStartedAt) { }
