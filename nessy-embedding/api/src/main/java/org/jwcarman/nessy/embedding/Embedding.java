package org.jwcarman.nessy.embedding;

import java.util.Arrays;
import java.util.Objects;

/**
 * A text, as the point in space an {@link Embedder} put it at.
 *
 * <p>Carries the model that produced it, because a distance between vectors from two models means
 * nothing: a store records the model beside every vector and refuses a query from another.
 *
 * <p>Compares by content, as a record of an array would not.
 *
 * @param model the embedding model's name, as its {@link Embedder} reports it
 * @param vector the coordinates; never empty, and every vector from one model has the same length
 */
public record Embedding(String model, float[] vector) {

  public Embedding {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(vector, "vector must not be null");
    if (model.isBlank()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    if (vector.length == 0) {
      throw new IllegalArgumentException("a vector must have at least one dimension");
    }
    vector = vector.clone();
  }

  public int dimension() {
    return vector.length;
  }

  /**
   * How alike two texts are, as the cosine of the angle between them: 1 is the same direction, 0 is
   * unrelated, -1 is opposite. Only meaningful between vectors from one model, so any other pairing
   * is refused rather than answered.
   */
  public double similarity(Embedding other) {
    Objects.requireNonNull(other, "other must not be null");
    if (!model.equals(other.model)) {
      throw new IllegalArgumentException(
          "cannot compare an embedding from " + model + " with one from " + other.model);
    }
    if (vector.length != other.vector.length) {
      throw new IllegalArgumentException(
          "cannot compare %d dimensions with %d".formatted(vector.length, other.vector.length));
    }
    double dot = 0;
    double mine = 0;
    double theirs = 0;
    for (int i = 0; i < vector.length; i++) {
      dot += (double) vector[i] * other.vector[i];
      mine += (double) vector[i] * vector[i];
      theirs += (double) other.vector[i] * other.vector[i];
    }
    if (mine == 0 || theirs == 0) {
      return 0;
    }
    return dot / (Math.sqrt(mine) * Math.sqrt(theirs));
  }

  @Override
  public float[] vector() {
    return vector.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Embedding(String thatModel, float[] thatVector)
        && model.equals(thatModel)
        && Arrays.equals(vector, thatVector);
  }

  @Override
  public int hashCode() {
    return Objects.hash(model, Arrays.hashCode(vector));
  }

  @Override
  public String toString() {
    return "Embedding[model=" + model + ", dimension=" + vector.length + "]";
  }
}
