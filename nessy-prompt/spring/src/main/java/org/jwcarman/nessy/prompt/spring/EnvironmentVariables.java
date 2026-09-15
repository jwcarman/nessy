package org.jwcarman.nessy.prompt.spring;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.prompt.PromptVariableSource;
import org.springframework.core.env.PropertyResolver;

/**
 * A Spring {@code Environment} as a source of variables: {@code ${app.persona}} in a prompt is
 * whatever {@code app.persona} is in the properties, the same for every agent.
 */
public final class EnvironmentVariables {

  private EnvironmentVariables() {}

  public static PromptVariableSource of(PropertyResolver environment) {
    Objects.requireNonNull(environment, "environment must not be null");
    return (_, name) -> Optional.ofNullable(environment.getProperty(name));
  }
}
