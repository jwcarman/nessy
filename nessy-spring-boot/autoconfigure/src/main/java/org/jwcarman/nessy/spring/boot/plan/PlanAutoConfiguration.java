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

import javax.sql.DataSource;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.planning.JdbcPlans;
import org.jwcarman.nessy.planning.PlanTools;
import org.jwcarman.nessy.planning.Plans;
import org.jwcarman.nessy.spring.boot.JdbcBackendAutoConfiguration;
import org.jwcarman.nessy.spring.boot.JdbcBackendAutoConfiguration.NessySchema;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The plan store, installed on every agent of both doors when it and the JDBC backend are on the
 * classpath. The plan keeps its tasks in {@code nessy_plan_task}, a table the planning module's
 * schema file creates when the JDBC backend initializes the schema, so without that backend there
 * is no feature.
 *
 * <p>Adding the starter adds the plan; an application that does not want its agents to keep one
 * says {@code nessy.plan.enabled=false}. An application that declares its own {@link Plans} bean
 * keeps the starter out; wiring the plan by hand beside the starter's feature, without declaring
 * the bean, would make the harness refuse to build, with "two ambient sources offer the kind
 * 'plan'".
 *
 * <p>One plan store per agent type: {@link JdbcPlans} is keyed by type, and a feature runs once per
 * harness, so the feature builds the store for the type it is equipping. The object is a handle on
 * a data source and a codec, cheap to make and safe to make again.
 */
@AutoConfiguration(
    after = {
      NessyAutoConfiguration.class,
      JdbcBackendAutoConfiguration.class,
      DataSourceAutoConfiguration.class
    })
@ConditionalOnClass(
    name = "org.jwcarman.nessy.backend.jdbc.JdbcDirectBackend",
    value = JdbcPlans.class)
@ConditionalOnBean({DataSource.class, CodecFactory.class})
@ConditionalOnProperty(name = "nessy.plan.enabled", havingValue = "true", matchIfMissing = true)
public class PlanAutoConfiguration {

  /** The feature both doors collect: the plan as ambient background and the one tool. */
  @Bean
  @ConditionalOnMissingBean(value = Plans.class, name = "nessyPlanFeature")
  public Customizer<HarnessConfig<?>> nessyPlanFeature(
      DataSource dataSource,
      CodecFactory codecs,
      // Not read: the schema bean is the dependency that makes Spring create the tables first.
      NessySchema schema) {
    return config ->
        PlanTools.feature(new JdbcPlans(dataSource, config.agentType(), codecs)).customize(config);
  }
}
