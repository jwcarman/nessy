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
package org.jwcarman.nessy.spring.boot.plan;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.planning.JdbcPlans;
import org.jwcarman.nessy.planning.Plans;
import org.jwcarman.nessy.spring.boot.JdbcBackendAutoConfiguration;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.jwcarman.nessy.spring.boot.plan.PlanFeatures.Recording;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The plan store on the classpath installs itself, unless told not to.
 *
 * <p>H2 and no tables, like every wiring test here: the assertions are about which beans exist and
 * what the feature does to a harness config, never about a task reaching a database. That part is
 * in {@link PlanPerTypeTest}, over PostgreSQL.
 */
@DisplayName("The plan auto-configuration")
class PlanAutoConfigurationTest {

  @Configuration(proxyBeanMethods = false)
  static class ADatabase {

    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();
    }

    @Bean
    PlatformTransactionManager transactions(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ItsOwnPlans {

    @Bean
    Plans plans(DataSource dataSource, CodecFactory codecs) {
      return new JdbcPlans(dataSource, new AgentType("chat"), codecs);
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class,
                  NessyAutoConfiguration.class,
                  JdbcBackendAutoConfiguration.class,
                  PlanAutoConfiguration.class))
          .withUserConfiguration(ADatabase.class)
          .withPropertyValues("nessy.initialize-schema=false");

  @Test
  void the_plan_feature_is_there_by_default() {
    runner.run(context -> assertThat(context).hasBean("nessyPlanFeature"));
  }

  @Test
  void the_property_turns_it_off() {
    runner
        .withPropertyValues("nessy.plan.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean("nessyPlanFeature"));
  }

  @Test
  void an_application_with_its_own_plans_bean_gets_no_feature() {
    runner
        .withUserConfiguration(ItsOwnPlans.class)
        .run(context -> assertThat(context).doesNotHaveBean("nessyPlanFeature"));
  }

  @Test
  void without_a_data_source_there_is_no_feature_and_no_failure() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                NessyAutoConfiguration.class,
                PlanAutoConfiguration.class))
        .withPropertyValues("nessy.initialize-schema=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean("nessyPlanFeature");
            });
  }

  @Test
  void the_feature_installs_the_ambient_and_the_tool() {
    runner.run(
        context -> {
          Recording config = new Recording(new AgentType("chat"));

          PlanFeatures.feature(context).customize(config);

          assertThat(config.ambients).hasSize(1);
          assertThat(config.tools)
              .extracting(tool -> tool.name().value())
              .containsExactly("update_plan");
        });
  }

  /**
   * The ordinary application declares no DataSource of its own: Boot makes it from
   * spring.datasource.url. A data source Boot makes, with none declared by the user, is enough for
   * the feature to appear.
   */
  @Test
  void a_data_source_boot_makes_is_enough() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                NessyAutoConfiguration.class,
                JdbcBackendAutoConfiguration.class,
                PlanAutoConfiguration.class))
        .withPropertyValues(
            "nessy.initialize-schema=false",
            "spring.datasource.type=org.springframework.jdbc.datasource.SimpleDriverDataSource",
            "spring.datasource.url=jdbc:h2:mem:plan-ordering;DB_CLOSE_DELAY=-1")
        .run(context -> assertThat(context).hasBean("nessyPlanFeature"));
  }

  /**
   * The Spring Boot guide says an application that wants neither backend excludes both
   * auto-configurations and nothing downstream demands one. With nessy-backend-jdbc still on the
   * classpath and a DataSource of its own, that application must still start, and gets no feature:
   * the table the feature writes to is created by the backend it excluded.
   */
  @Test
  void an_application_that_excludes_the_jdbc_backend_still_starts() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                NessyAutoConfiguration.class,
                PlanAutoConfiguration.class))
        .withUserConfiguration(ADatabase.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean("nessyPlanFeature");
            });
  }
}
