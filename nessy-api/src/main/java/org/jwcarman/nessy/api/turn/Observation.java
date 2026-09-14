package org.jwcarman.nessy.api.turn;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.block.Block;

/**
 * What opened a turn, as the model would read it.
 *
 * <p>Turn-shaped rather than entry-shaped: a projection hands out conversations, and how one was
 * stored is nobody else's business.
 */
public record Observation(Seq seq, List<Block.ObservationContent> blocks) {

  public Observation {
    Objects.requireNonNull(blocks, "blocks must not be null");
    if (blocks.isEmpty()) {
      throw new IllegalArgumentException("an observation must have at least one block");
    }
    blocks = List.copyOf(blocks);
  }
}
