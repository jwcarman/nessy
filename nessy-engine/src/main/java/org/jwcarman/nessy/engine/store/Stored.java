package org.jwcarman.nessy.engine.store;

import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.history.HistoryEntry;

/**
 * One history row, decoded, with the token estimate that was written beside it.
 *
 * <p>Read by {@link Turns} on its way to building turns and never seen outside this package: what
 * leaves here is a {@link Turn}, and what a row looked like is nobody else's business.
 */
record Stored(HistoryEntry message, int tokens) {}
