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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.jdbc.Schemas;
import org.jwcarman.nessy.planning.Plan;
import org.jwcarman.nessy.planning.PlanTools;
import org.jwcarman.nessy.spring.boot.JdbcBackendAutoConfiguration;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.jwcarman.nessy.spring.boot.plan.PlanFeatures.Recording;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The feature builds one plan store per agent type: a plan written through the tool one type got is
 * not in the ambient another type got. Over a real database, because H2 with no tables cannot take
 * the write.
 */
@Tag("container")
@DisplayName("The plan feature, per agent type")
class PlanPerTypeTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  @Configuration(proxyBeanMethods = false)
  static class ADatabase {

    @Bean
    DataSource dataSource() {
      DataSource database =
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
      Schemas.initialize(database);
      return database;
    }

    @Bean
    PlatformTransactionManager transactions(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
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
  void a_plan_written_for_one_type_is_not_in_the_ambient_of_another() {
    runner.run(
        context -> {
          AgentType typeA = new AgentType("type-a");
          AgentType typeB = new AgentType("type-b");
          AgentId agent = new AgentId(UUID.randomUUID());
          Recording forA = new Recording(typeA);
          Recording forB = new Recording(typeB);
          PlanFeatures.feature(context).customize(forA);
          PlanFeatures.feature(context).customize(forB);

          PlanFeatures.call(
              forA.tools.stream()
                  .filter(tool -> tool.name().value().equals("update_plan"))
                  .findFirst()
                  .orElseThrow(),
              typeA,
              agent,
              new PlanTools.UpdatePlan(
                  List.of(new PlanTools.PlannedTask("write the plan", Plan.Status.PENDING))));

          assertThat(ambientText(forA.ambients.getFirst(), agent)).contains("write the plan");
          assertThat(forB.ambients.getFirst().forAgent(agent)).isEmpty();
        });
  }

  private static String ambientText(AmbientSource source, AgentId agent) {
    Optional<Ambient> ambient = source.forAgent(agent);
    return ((Block.Text) ambient.orElseThrow().content().getFirst()).text();
  }
}
