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
package org.jwcarman.nessy.spring.boot.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.jwcarman.nessy.spring.boot.notebook.NotebookFeatures.Recording;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * The notebook on the classpath installs itself, unless told not to.
 *
 * <p>H2 and no tables, like every wiring test here: the assertions are about which beans exist and
 * what the feature does to a harness config, never about a note reaching a database. That part is
 * in {@link NotebookPerTypeTest}, over PostgreSQL.
 */
@DisplayName("The notebook auto-configuration")
class NotebookAutoConfigurationTest {

  @Configuration(proxyBeanMethods = false)
  static class ADatabase {

    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class,
                  NessyAutoConfiguration.class,
                  NotebookAutoConfiguration.class))
          .withUserConfiguration(ADatabase.class)
          .withPropertyValues("nessy.initialize-schema=false");

  @Test
  void the_notebook_feature_is_there_by_default() {
    runner.run(context -> assertThat(context).hasBean("nessyNotebookFeature"));
  }

  @Test
  void the_property_turns_it_off() {
    runner
        .withPropertyValues("nessy.notebook.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean("nessyNotebookFeature"));
  }

  @Test
  void without_a_data_source_there_is_no_feature_and_no_failure() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                NessyAutoConfiguration.class,
                NotebookAutoConfiguration.class))
        .withPropertyValues("nessy.initialize-schema=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean("nessyNotebookFeature");
            });
  }

  @Test
  void the_feature_installs_the_index_and_four_tools_on_the_harness_it_is_given() {
    runner.run(
        context -> {
          Recording config = new Recording(new AgentType("chat"));

          NotebookFeatures.feature(context).customize(config);

          assertThat(config.ambients).hasSize(1);
          assertThat(config.tools)
              .extracting(tool -> tool.name().value())
              .containsExactly("remember", "revise", "recall", "forget");
        });
  }
}
