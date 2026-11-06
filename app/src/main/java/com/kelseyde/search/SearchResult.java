package com.kelseyde.calvin.search;

import com.kelseyde.calvin.board.Move;

import java.time.Duration;
import java.time.Instant;

public record SearchResult(int eval, Move move, int depth, int seldepth, long time, int nodes, long nps) {

    public static SearchResult of(Move move) {
        return new SearchResult(0, move, 0, 0, 0, 0, 0);
    }

    public static SearchResult of(ThreadData td, SearchLimits limits) {
        long elapsedNanos = limits.start() != null
                ? Duration.between(limits.start(), Instant.now()).toNanos() : 0L;
        long millis = elapsedNanos > 0L ? elapsedNanos / 1_000_000L : 0L;
        long nps = td.nodes > 0 && elapsedNanos > 0L
                ? (long) (((double) td.nodes * 1_000_000_000.0) / elapsedNanos) : 0L;
        return new SearchResult(td.bestScore(), td.bestMove(), td.depth, td.seldepth, millis, td.nodes, nps);
    }

}
