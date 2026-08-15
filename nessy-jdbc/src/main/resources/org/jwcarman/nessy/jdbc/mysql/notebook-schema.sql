-- Copyright © 2026 James Carman
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- MySQL -- see schema.sql's header for the verified-version and type-mapping notes. No jsonb
-- column here, so no cast concerns -- `varchar`/`text` are native on MySQL and need no width
-- decision beyond the ones chosen below.

CREATE TABLE IF NOT EXISTS nessy_notebook (
  subject_id varchar(255)  NOT NULL,
  name       varchar(255)  NOT NULL,
  hook       varchar(1024) NOT NULL,
  body       text          NOT NULL,
  PRIMARY KEY (subject_id, name)
);
