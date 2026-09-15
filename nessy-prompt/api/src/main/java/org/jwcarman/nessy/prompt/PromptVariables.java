package org.jwcarman.nessy.prompt;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a template is rendered with: a name, answered or not. Asked by name rather than handed a
 * map, because that is what filling a hole is, and because the answer may cost something -- a
 * lookup, a clock -- that should only be paid for the names a template actually has.
 */
@FunctionalInterface
public interface PromptVariables {

  Optional<String> variable(String name);

  static PromptVariables of(Map<String, String> values) {
    Map<String, String> copy =
        Map.copyOf(Objects.requireNonNull(values, "values must not be null"));
    return name -> Optional.ofNullable(copy.get(name));
  }

  static PromptVariables none() {
    return _ -> Optional.empty();
  }
}
