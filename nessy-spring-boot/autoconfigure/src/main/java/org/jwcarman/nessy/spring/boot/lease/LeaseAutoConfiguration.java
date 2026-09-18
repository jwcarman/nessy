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

import javax.sql.DataSource;
import org.jwcarman.nessy.lease.JdbcLeases;
import org.jwcarman.nessy.lease.Leases;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Leases over the application's database, when {@code nessy-lease} is on the classpath. After the
 * starter's own auto-configuration, whose schema step creates the table.
 */
@AutoConfiguration(after = NessyAutoConfiguration.class)
@ConditionalOnClass(JdbcLeases.class)
public class LeaseAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public Leases nessyLeases(DataSource dataSource, NessyAutoConfiguration.NessySchema schema) {
    return new JdbcLeases(dataSource);
  }
}
