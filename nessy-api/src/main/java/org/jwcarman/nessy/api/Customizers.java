package org.jwcarman.nessy.api;

import java.util.function.Consumer;

public class Customizers {
  private Customizers() {
    // Utility class
  }

  public static <T> Consumer<T> withDefaults() {
    return _ -> {};
  }
}
