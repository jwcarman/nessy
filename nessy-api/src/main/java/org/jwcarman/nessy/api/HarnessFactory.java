package org.jwcarman.nessy.api;

import java.util.function.Consumer;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.nessy.api.tool.Replies;

/**
 * Makes harnesses, holding the infrastructure every agent type is built from.
 *
 * <p>The split is deliberate: a caller supplies what is theirs -- the agent type's name, what it
 * is, how to read its observations -- and this supplies the stores, the transaction template, the
 * scheduler, the codec factory, and the provider and model to use when an agent type does not care
 * to choose. Nobody assembles a harness by hand, so nobody can assemble one wrongly.
 *
 * <p><b>Shared infrastructure, not shared machinery.</b> Two harnesses use the same tables and the
 * same scheduler the way they use the same JVM. Nothing else crosses between them: each gets its
 * own codec, its own model, its own dispatcher, its own schedule and its own rows.
 */
public interface HarnessFactory {

  /** For observations that are already what a model should read. */
  default Harness<String> create(Consumer<HarnessConfig<String>> customizer) {
    return create(String.class, customizer);
  }

  /** For an observation type that is not itself generic, which is nearly all of them. */
  default <O> Harness<O> create(Class<O> observationType, Consumer<HarnessConfig<O>> customizer) {
    return create(TypeRef.of(observationType), customizer);
  }

  /**
   * The general form, for an observation type that is itself generic.
   *
   * <p>{@link TypeRef#parameterized} is why this exists: a {@code TypeRef} cannot be captured for a
   * type variable, so the agent's own codec has to be composed from the caller's, and only a {@code
   * TypeRef} can carry that through.
   */
  <O> Harness<O> create(TypeRef<O> observationType, Consumer<HarnessConfig<O>> customizer);

  /**
   * Where a late answer comes back in. One for the whole factory rather than one per harness: a
   * reply token is opaque, so whoever holds one cannot say which kind of agent it belongs to.
   */
  Replies replies();
}
