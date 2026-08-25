/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.InstantSource;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.support.DelegatingSubstrate;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.slf4j.LoggerFactory;

/**
 * {@link HarnessConfig#finish()}'s durability-mismatch guard (continuum-adoption spec §11.1). It
 * warns only when it knows the tiers differ: a caller-supplied {@link Substrate} that is anything
 * other than {@link InMemorySubstrate} is durable, and a computation store the harness had to mint
 * itself — because {@link HarnessConfig#continuum} was never called — is volatile by construction.
 * A caller-supplied Continuum is not inspected and never warns; whoever supplies one is trusted to
 * have matched the tiers. The appender is wired directly onto {@link HarnessConfig}'s own class
 * logger, the same technique {@code TurnObserverLoggingTest} uses.
 */
class DurabilityMismatchWarningTest {

  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void wires_a_capturing_appender_onto_harness_configs_own_logger() {
    Logger classicLogger = (Logger) LoggerFactory.getLogger(HarnessConfig.class);
    classicLogger.setLevel(Level.TRACE);
    appender = new ListAppender<>();
    appender.start();
    classicLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    Logger classicLogger = (Logger) LoggerFactory.getLogger(HarnessConfig.class);
    classicLogger.detachAppender(appender);
    classicLogger.setLevel(null);
    HarnessTeardown.shutdownAllTracked();
  }

  /**
   * The seam's half of the guard: the same durable-looking substrate, but a Continuum supplied by
   * the caller. The harness cannot see through a Continuum to its repository, so it trusts the
   * caller and says nothing — this fails against a guard that warns on "supplied at all", or one
   * that still hardcodes the computation store as volatile.
   */
  @Test
  void a_durable_looking_substrate_with_a_caller_supplied_continuum_logs_nothing() {
    var substrate = new DelegatingSubstrate(new InMemorySubstrate());
    var model = new ScriptedModel(List.of());
    var supplied = new DefaultContinuum(new InMemoryContinuumRepository(), InstantSource.system());

    var harness =
        Nessy.harness(
            h ->
                h.model(model)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .substrate(substrate)
                    .continuum(supplied));
    HarnessTeardown.track(harness);

    assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.WARN);

    // S5841 guard, as below: prove the appender is live by driving the mismatched build through it.
    var mismatched =
        Nessy.harness(
            h ->
                h.model(model)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .substrate(substrate));
    HarnessTeardown.track(mismatched);
    assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN);
  }

  @Test
  void a_durable_looking_substrate_over_the_still_volatile_computation_store_logs_a_warning() {
    var substrate = new DelegatingSubstrate(new InMemorySubstrate());
    var model = new ScriptedModel(List.of());

    var harness =
        Nessy.harness(
            h ->
                h.model(model)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .substrate(substrate));
    HarnessTeardown.track(harness);

    List<ILoggingEvent> warnings =
        appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    assertThat(warnings).hasSize(1);
    assertThat(warnings.getFirst().getFormattedMessage()).contains("Durability mismatch");
  }

  /**
   * S5841: {@code noneMatch} on {@code appender.list} alone would pass vacuously if the appender
   * were silently mis-wired — a typo'd logger name, or {@link HarnessConfig} ceasing to log at any
   * level — since an empty list matches nothing by construction. Proven non-vacuous here by driving
   * a genuinely mismatched build through the SAME appender afterward and asserting it captures
   * exactly the one warning the first (matched) build must not have produced.
   */
  @Test
  void a_plain_in_memory_substrate_over_the_in_memory_computation_store_logs_nothing() {
    var model = new ScriptedModel(List.of());

    var harness =
        Nessy.harness(
            h ->
                h.model(model)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings()));
    HarnessTeardown.track(harness);

    assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.WARN);

    var mismatchedHarness =
        Nessy.harness(
            h ->
                h.model(model)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .substrate(new DelegatingSubstrate(new InMemorySubstrate())));
    HarnessTeardown.track(mismatchedHarness);

    assertThat(appender.list).hasSize(1).allMatch(event -> event.getLevel() == Level.WARN);
  }
}
