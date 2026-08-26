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
package org.jwcarman.nessy.model.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelProviderBootstrap;

/**
 * Drives {@link ModelDiscovery#select(Map, Iterable)} through its two seams — an env map and an
 * explicit registration list — so every outcome in the design's §4 is pinned with no real provider
 * on the classpath. A handful of tests, at the end, go through the real {@link
 * java.util.ServiceLoader} path against {@link RegisteredFakeBootstrap}, which is the only
 * registration this module's test classpath carries.
 */
class ModelDiscoveryTest {

  private static final FakeBootstrap ALPHA =
      new FakeBootstrap("alpha", "ALPHA_KEY", "alpha-default");
  private static final FakeBootstrap BETA = new FakeBootstrap("beta", "BETA_KEY", "beta-default");

  @Nested
  class With_nothing_registered {

    @Test
    void fails_naming_the_modules_that_would_register_something() {
      Map<String, String> env = Map.of("ALPHA_KEY", "present-but-nobody-claims-it");
      List<ModelProviderBootstrap> none = List.of();

      assertThatThrownBy(() -> ModelDiscovery.select(env, none))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no model provider modules are on the classpath")
          .hasMessageContaining("nessy-model-anthropic")
          .hasMessageContaining("nessy-model-openai")
          .hasMessageContaining("nessy-model-gemini");
    }
  }

  @Nested
  class With_one_registered {

    @Test
    void fails_naming_that_provider_and_its_variables_when_its_key_is_absent() {
      Map<String, String> env = Map.of("BETA_KEY", "present-but-beta-is-not-registered");
      List<ModelProviderBootstrap> only = List.of(ALPHA);

      assertThatThrownBy(() -> ModelDiscovery.select(env, only))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no model provider credentials found")
          .hasMessageContaining("alpha [ALPHA_KEY]")
          .hasMessageNotContaining("beta");
    }

    @Test
    void chooses_it_when_its_key_is_present() {
      var selection = ModelDiscovery.select(Map.of("ALPHA_KEY", "k"), List.of(ALPHA));

      assertThat(selection.providerName()).isEqualTo("alpha");
      assertThat(selection.model().id()).isEqualTo("alpha-default");
    }

    @Test
    void ignores_nessy_provider_however_wrong_because_one_candidate_is_no_tie() {
      var selection =
          ModelDiscovery.select(
              Map.of("ALPHA_KEY", "k", "NESSY_PROVIDER", "something-else"), List.of(ALPHA));

      assertThat(selection.providerName()).isEqualTo("alpha");
    }

    @Test
    void from_env_returns_the_bound_model_directly() {
      var model = ModelDiscovery.fromEnv(Map.of("ALPHA_KEY", "k"), List.of(ALPHA));

      assertThat(model.id()).isEqualTo("alpha-default");
    }
  }

  @Nested
  class With_two_registered_and_both_keys_present {

    private final Map<String, String> both = Map.of("ALPHA_KEY", "a", "BETA_KEY", "b");

    @Test
    void fails_naming_both_when_nessy_provider_is_unset() {
      List<ModelProviderBootstrap> pair = List.of(ALPHA, BETA);

      assertThatThrownBy(() -> ModelDiscovery.select(both, pair))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("multiple model providers can bootstrap from this environment")
          .hasMessageContaining("(alpha, beta)")
          .hasMessageContaining("set NESSY_PROVIDER")
          .hasMessageNotContaining("names none of them");
    }

    @Test
    void nessy_provider_naming_one_of_them_chooses_it_silently() {
      var env = withProvider(both, "beta");

      var selection = ModelDiscovery.select(env, List.of(ALPHA, BETA));

      assertThat(selection.providerName()).isEqualTo("beta");
      assertThat(selection.model().id()).isEqualTo("beta-default");
    }

    @Test
    void nessy_provider_is_read_case_insensitively() {
      var env = withProvider(both, "BeTa");

      var selection = ModelDiscovery.select(env, List.of(ALPHA, BETA));

      assertThat(selection.providerName()).isEqualTo("beta");
    }

    @Test
    void fails_naming_both_and_the_value_when_nessy_provider_names_neither() {
      var env = withProvider(both, "gamma");
      List<ModelProviderBootstrap> pair = List.of(ALPHA, BETA);

      assertThatThrownBy(() -> ModelDiscovery.select(env, pair))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("(alpha, beta)")
          .hasMessageContaining("NESSY_PROVIDER=gamma names none of them");
    }

    @Test
    void a_blank_nessy_provider_fails_like_an_unset_one() {
      var env = withProvider(both, "   ");
      List<ModelProviderBootstrap> pair = List.of(ALPHA, BETA);

      assertThatThrownBy(() -> ModelDiscovery.select(env, pair))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("(alpha, beta)")
          .hasMessageNotContaining("names none of them");
    }

    @Test
    void a_registered_provider_whose_key_is_absent_is_not_a_candidate() {
      // Only alpha's key is present, so two registrations still yield one candidate: no tie.
      var selection = ModelDiscovery.select(Map.of("ALPHA_KEY", "a"), List.of(ALPHA, BETA));

      assertThat(selection.providerName()).isEqualTo("alpha");
    }

    private static Map<String, String> withProvider(Map<String, String> env, String provider) {
      var copy = new HashMap<>(env);
      copy.put("NESSY_PROVIDER", provider);
      return Map.copyOf(copy);
    }
  }

  @Nested
  class Registration_errors {

    @Test
    void two_registrations_sharing_a_name_fail_naming_the_token_and_both_classes() {
      var alphaAgain = new OtherFakeBootstrap("alpha", "OTHER_KEY", "other-default");
      List<ModelProviderBootstrap> clash = List.of(ALPHA, alphaAgain);
      Map<String, String> env = Map.of();

      assertThatThrownBy(() -> ModelDiscovery.select(env, clash))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("share the name 'alpha'")
          .hasMessageContaining(FakeBootstrap.class.getName())
          .hasMessageContaining(OtherFakeBootstrap.class.getName());
    }

    @Test
    void a_bootstrap_that_throws_on_a_present_key_propagates() {
      var broken = FakeBootstrap.throwingOnPresentKey("broken", "BROKEN_KEY");
      List<ModelProviderBootstrap> only = List.of(broken);
      Map<String, String> env = Map.of("BROKEN_KEY", "present");

      assertThatThrownBy(() -> ModelDiscovery.select(env, only))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("BROKEN_KEY is malformed");
    }

    @Test
    void a_blank_name_fails_naming_the_class() {
      var blank = new FakeBootstrap("  ", "BLANK_KEY", "blank-default");
      List<ModelProviderBootstrap> only = List.of(blank);
      Map<String, String> env = Map.of();

      assertThatThrownBy(() -> ModelDiscovery.select(env, only))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(FakeBootstrap.class.getName())
          .hasMessageContaining("blank name()");
    }

    @Test
    void a_non_lowercase_name_fails_naming_the_class_and_the_name() {
      var acme = new FakeBootstrap("Acme", "ACME_KEY", "acme-default");
      List<ModelProviderBootstrap> only = List.of(acme);
      Map<String, String> env = Map.of();

      assertThatThrownBy(() -> ModelDiscovery.select(env, only))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(FakeBootstrap.class.getName())
          .hasMessageContaining("'Acme'");
    }

    @Test
    void null_environment_variables_fail_naming_the_class() {
      var broken = FakeBootstrap.withNullVariables("broken");
      List<ModelProviderBootstrap> only = List.of(broken);
      Map<String, String> env = Map.of();

      assertThatThrownBy(() -> ModelDiscovery.select(env, only))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining(FakeBootstrap.class.getName())
          .hasMessageContaining("environmentVariables()");
    }
  }

  /**
   * Bootstrapping BUILDS a gateway — an SDK client, a connection pool, threads — for every
   * candidate that applies, and only one of them can win. Discovery built the losers, so discovery
   * closes them (fix round, 2026-08-26): before this, a two-key environment leaked a whole gateway
   * on every successful call, and leaked BOTH on the tiebreak throw, where nothing is returned that
   * a caller could close.
   */
  @Nested
  class The_gateways_nobody_chose {

    @Test
    void a_losing_candidate_is_closed_before_the_selection_is_returned() {
      var alpha = new FakeBootstrap("alpha", "ALPHA_KEY", "alpha-default");
      var beta = new FakeBootstrap("beta", "BETA_KEY", "beta-default");
      var env = Map.of("ALPHA_KEY", "k", "BETA_KEY", "k", "NESSY_PROVIDER", "alpha");

      var selection = ModelDiscovery.select(env, List.of(alpha, beta));

      assertThat(selection.providerName()).isEqualTo("alpha");
      assertThat(beta.lastProvider().isClosed()).isTrue();
    }

    /** And the winner is emphatically NOT closed — it is the one the caller is about to use. */
    @Test
    void the_chosen_candidate_is_left_open_for_its_caller() {
      var alpha = new FakeBootstrap("alpha", "ALPHA_KEY", "alpha-default");
      var beta = new FakeBootstrap("beta", "BETA_KEY", "beta-default");
      var env = Map.of("ALPHA_KEY", "k", "BETA_KEY", "k", "NESSY_PROVIDER", "alpha");

      var selection = ModelDiscovery.select(env, List.of(alpha, beta));

      assertThat(alpha.lastProvider().isClosed()).isFalse();
      selection.close();
      assertThat(alpha.lastProvider().isClosed()).isTrue();
    }

    /** The one candidate of a one-candidate environment is never closed either. */
    @Test
    void an_only_candidate_is_never_closed() {
      var alpha = new FakeBootstrap("alpha", "ALPHA_KEY", "alpha-default");

      ModelDiscovery.select(Map.of("ALPHA_KEY", "k"), List.of(alpha));

      assertThat(alpha.lastProvider().isClosed()).isFalse();
    }

    /**
     * The leak that had no owner at all: an ambiguous environment throws, so no {@code Selection}
     * is returned and nothing the caller holds could ever release either gateway.
     */
    @Test
    void a_tiebreak_failure_closes_every_candidate_it_built() {
      var alpha = new FakeBootstrap("alpha", "ALPHA_KEY", "alpha-default");
      var beta = new FakeBootstrap("beta", "BETA_KEY", "beta-default");
      var env = Map.of("ALPHA_KEY", "k", "BETA_KEY", "k");
      List<ModelProviderBootstrap> bootstraps = List.of(alpha, beta);

      assertThatThrownBy(() -> ModelDiscovery.select(env, bootstraps))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("multiple model providers can bootstrap");

      assertThat(alpha.lastProvider().isClosed()).isTrue();
      assertThat(beta.lastProvider().isClosed()).isTrue();
    }
  }

  @Nested
  class The_model_id {

    @Test
    void comes_from_the_winners_default_when_nessy_model_is_unset() {
      var selection = ModelDiscovery.select(Map.of("ALPHA_KEY", "k"), List.of(ALPHA));

      assertThat(selection.model().id()).isEqualTo("alpha-default");
    }

    @Test
    void nessy_model_wins_over_the_default() {
      var selection =
          ModelDiscovery.select(
              Map.of("ALPHA_KEY", "k", "NESSY_MODEL", "alpha-large"), List.of(ALPHA));

      assertThat(selection.model().id()).isEqualTo("alpha-large");
    }

    @Test
    void a_blank_nessy_model_is_ignored() {
      var selection =
          ModelDiscovery.select(Map.of("ALPHA_KEY", "k", "NESSY_MODEL", "   "), List.of(ALPHA));

      assertThat(selection.model().id()).isEqualTo("alpha-default");
    }
  }

  @Nested
  class Selection_record {

    @Test
    void rejects_a_null_provider() {
      var provider = ALPHA.bootstrap(Map.of("ALPHA_KEY", "k")).orElseThrow();
      var model = provider.model("m");

      assertThatThrownBy(() -> new ModelDiscovery.Selection(null, model, "alpha"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_a_null_model() {
      var provider = ALPHA.bootstrap(Map.of("ALPHA_KEY", "k")).orElseThrow();

      assertThatThrownBy(() -> new ModelDiscovery.Selection(provider, null, "alpha"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_a_null_provider_name() {
      var provider = ALPHA.bootstrap(Map.of("ALPHA_KEY", "k")).orElseThrow();
      var model = provider.model("m");

      assertThatThrownBy(() -> new ModelDiscovery.Selection(provider, model, null))
          .isInstanceOf(NullPointerException.class);
    }

    /**
     * A selection closes the gateway discovery built for it — the whole reason the gateway is a
     * component of this record (ruled 2026-08-26). Without it, an application that discovers a
     * provider has no handle on the SDK client's connection pool at all.
     */
    @Test
    void closing_a_selection_closes_the_gateway_it_came_from() {
      var closed = new AtomicBoolean();
      var provider =
          new ModelProvider() {

            @Override
            public Model model(String id) {
              throw new UnsupportedOperationException("not needed here");
            }

            @Override
            public void close() {
              closed.set(true);
            }
          };
      var model = ALPHA.bootstrap(Map.of("ALPHA_KEY", "k")).orElseThrow().model("m");

      new ModelDiscovery.Selection(provider, model, "alpha").close();

      assertThat(closed).isTrue();
    }

    /** The default is a no-op, so a gateway holding nothing needs no close of its own. */
    @Test
    void a_gateway_that_holds_nothing_closes_silently() {
      var provider = ALPHA.bootstrap(Map.of("ALPHA_KEY", "k")).orElseThrow();
      var selection = new ModelDiscovery.Selection(provider, provider.model("m"), "alpha");

      assertThatCode(selection::close).doesNotThrowAnyException();
    }
  }

  @Nested
  class Through_the_real_service_loader {

    @Test
    void finds_the_registration_in_this_modules_test_services_file() {
      var selection = ModelDiscovery.select(Map.of(RegisteredFakeBootstrap.ENV_VAR, "k"));

      assertThat(selection.providerName()).isEqualTo("registered");
      assertThat(selection.model().id()).isEqualTo("registered-default");
    }

    @Test
    void the_no_credentials_message_names_only_what_is_registered() {
      Map<String, String> env = Map.of();

      assertThatThrownBy(() -> ModelDiscovery.select(env))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("registered [REGISTERED_FAKE_KEY]");
    }

    @Test
    void from_env_through_the_service_loader_finds_the_registration() {
      var model = ModelDiscovery.fromEnv(Map.of(RegisteredFakeBootstrap.ENV_VAR, "k"));

      assertThat(model.id()).isEqualTo("registered-default");
    }

    @Test
    void the_public_from_env_fails_naming_what_is_registered() {
      assertThatThrownBy(ModelDiscovery::fromEnv)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("registered [REGISTERED_FAKE_KEY]");
    }
  }
}
