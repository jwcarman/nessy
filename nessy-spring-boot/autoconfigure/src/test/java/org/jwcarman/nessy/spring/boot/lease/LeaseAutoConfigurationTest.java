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
package org.jwcarman.nessy.spring.boot.lease;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.lease.Leases;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("The lease auto-configuration")
class LeaseAutoConfigurationTest {

  @Test
  void a_leases_bean_appears_once_the_tables_exist() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LeaseAutoConfiguration.class))
        .withBean(DataSource.class, PGSimpleDataSource::new)
        .withBean(
            NessyAutoConfiguration.NessySchema.class,
            () -> new NessyAutoConfiguration.NessySchema(false))
        .run(context -> assertThat(context).hasSingleBean(Leases.class));
  }
}
