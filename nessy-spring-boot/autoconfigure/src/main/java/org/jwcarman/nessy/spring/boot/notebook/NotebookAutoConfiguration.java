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
import org.jwcarman.nessy.memory.notebook.NotebookTools;
import org.jwcarman.nessy.spring.boot.NessyAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The notebook, installed on every agent of both doors when it is on the classpath.
 *
 * <p>Adding the starter adds the notebook; an application that does not want its agents to keep
 * notes says {@code nessy.notebook.enabled=false}. One that wires the notebook by hand should say
 * the same, or its agents hold every tool twice.
 *
 * <p>One notebook per agent type: {@link JdbcNotebook} is keyed by type, and a feature runs once
 * per harness, so the feature builds the notebook for the type it is equipping. The object is a
 * handle on a data source and a codec, cheap to make and safe to make again.
 */
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, NessyAutoConfiguration.class})
@ConditionalOnClass(JdbcNotebook.class)
@ConditionalOnBean({DataSource.class, CodecFactory.class})
@ConditionalOnProperty(name = "nessy.notebook.enabled", havingValue = "true", matchIfMissing = true)
public class NotebookAutoConfiguration {

  /** The feature both doors collect: the index and the four tools, keyed to the harness's type. */
  @Bean
  @ConditionalOnMissingBean(name = "nessyNotebookFeature")
  public Customizer<HarnessConfig<?>> nessyNotebookFeature(
      DataSource dataSource, CodecFactory codecs) {
    return config ->
        NotebookTools.feature(new JdbcNotebook(dataSource, config.agentType(), codecs))
            .customize(config);
  }
}
