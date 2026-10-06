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

import javax.sql.DataSource;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.memory.notebook.JdbcNotebook;
import org.jwcarman.nessy.memory.notebook.Notebook;
import org.jwcarman.nessy.memory.notebook.NotebookTools;
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
 * The notebook, installed on every agent of both doors when it and the JDBC backend are on the
 * classpath. The notebook keeps its notes in {@code nessy_note}, a table the memory module's own
 * {@code nessy-schema.sql} creates when the JDBC backend initializes the schema, so without that
 * backend there is no feature. The conditions are a {@code DataSource}, a {@code CodecFactory} and
 * the JDBC backend's schema bean, so excluding the JDBC backend auto-configuration keeps the
 * feature out.
 *
 * <p>Adding the starter adds the notebook; an application that does not want its agents to keep
 * notes says {@code nessy.notebook.enabled=false}. An application that declares its own {@link
 * Notebook} bean keeps the starter out; wiring the notebook by hand beside the starter's feature
 * would make the harness refuse to build, with "two ambient sources offer the kind 'notebook'".
 *
 * <p>One notebook per agent type: {@link JdbcNotebook} is keyed by type, and a feature runs once
 * per harness, so the feature builds the notebook for the type it is equipping. The object is a
 * handle on a data source and a codec, cheap to make and safe to make again.
 */
@AutoConfiguration(
    after = {
      NessyAutoConfiguration.class,
      JdbcBackendAutoConfiguration.class,
      DataSourceAutoConfiguration.class
    })
@ConditionalOnClass(
    name = "org.jwcarman.nessy.backend.jdbc.JdbcDirectBackend",
    value = JdbcNotebook.class)
@ConditionalOnBean({DataSource.class, CodecFactory.class, NessySchema.class})
@ConditionalOnProperty(name = "nessy.notebook.enabled", havingValue = "true", matchIfMissing = true)
public class NotebookAutoConfiguration {

  /** The feature both doors collect: the index and the four tools, keyed to the harness's type. */
  @Bean
  @ConditionalOnMissingBean(value = Notebook.class, name = "nessyNotebookFeature")
  public Customizer<HarnessConfig<?>> nessyNotebookFeature(
      DataSource dataSource,
      CodecFactory codecs,
      // Not read: the schema bean is the dependency that makes Spring create the tables first.
      NessySchema schema) {
    return config ->
        NotebookTools.feature(new JdbcNotebook(dataSource, config.agentType(), codecs))
            .customize(config);
  }
}
