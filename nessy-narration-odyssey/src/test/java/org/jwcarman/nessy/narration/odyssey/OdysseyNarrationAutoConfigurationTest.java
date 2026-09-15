package org.jwcarman.nessy.narration.odyssey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.spi.narration.Narrator;
import org.jwcarman.odyssey.autoconfigure.OdysseyAutoConfiguration;
import org.jwcarman.odyssey.core.Odyssey;
import org.jwcarman.substrate.core.autoconfigure.SubstrateAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Wiring the narrator")
class OdysseyNarrationAutoConfigurationTest {

  /** What an application brings: a mapper. Substrate's in-memory backend needs nothing else. */
  @Configuration(proxyBeanMethods = false)
  static class AnApplication {
    @Bean
    ObjectMapper mapper() {
      return JsonMapper.builder().build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AnApplicationWithItsOwnNarrator {
    @Bean
    Narrator mine() {
      return Narrator.silent();
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  SubstrateCodecAutoConfiguration.class,
                  SubstrateAutoConfiguration.class,
                  OdysseyAutoConfiguration.class,
                  OdysseyNarrationAutoConfiguration.class))
          .withUserConfiguration(AnApplication.class);

  @Test
  @DisplayName("an Odyssey on the classpath and a mapper in the context is the whole setup")
  void with_odyssey_present_the_narrator_is_odysseys() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(Odyssey.class);
          assertThat(context).hasSingleBean(AgentStreams.class);
          assertThat(context).getBean(Narrator.class).isInstanceOf(OdysseyNarrator.class);
        });
  }

  @Test
  @DisplayName("and what it narrates is journaled, in memory here")
  void narrating_writes_to_the_journal() {
    runner.run(
        context -> {
          Narrator narrator = context.getBean(Narrator.class);
          AgentId agentId = new AgentId(UUID.randomUUID());
          // No exception is the assertion: the in-memory journal accepted the entry. What it
          // holds is read back over SSE, which the web example exercises end to end.
          narrator.narrate(new AgentType("chat"), agentId, new AgentEvent.ContentDelta("hi"));
          assertThat(
                  context
                      .getBean(AgentStreams.class)
                      .publish(
                          new AgentType("chat"),
                          agentId,
                          "note",
                          context.getBean(ObjectMapper.class).createObjectNode()))
              .isNotBlank();
        });
  }

  @Test
  @DisplayName("a narrator the application declared is left alone")
  void an_applications_own_narrator_wins() {
    runner
        .withUserConfiguration(AnApplicationWithItsOwnNarrator.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Narrator.class);
              assertThat(context).doesNotHaveBean(OdysseyNarrator.class);
              assertThat(context).hasSingleBean(AgentStreams.class);
            });
  }

  @Test
  @DisplayName("without an Odyssey there is nothing here, and the engine's silent default stands")
  void without_odyssey_nothing_is_declared() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OdysseyNarrationAutoConfiguration.class))
        .withUserConfiguration(AnApplication.class)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(AgentStreams.class);
              assertThat(context).doesNotHaveBean(Narrator.class);
            });
  }

  @Test
  void the_ttl_is_configurable() {
    runner
        .withPropertyValues(
            "nessy.narration.odyssey.inactivity-ttl=2h", "nessy.narration.odyssey.entry-ttl=30m")
        .run(
            context -> {
              OdysseyNarrationProperties properties =
                  context.getBean(OdysseyNarrationProperties.class);
              assertThat(properties.ttl().inactivityTtl()).hasHours(2);
              assertThat(properties.ttl().entryTtl()).hasMinutes(30);
              assertThat(properties.ttl().retentionTtl()).hasHours(1);
            });
  }
}
