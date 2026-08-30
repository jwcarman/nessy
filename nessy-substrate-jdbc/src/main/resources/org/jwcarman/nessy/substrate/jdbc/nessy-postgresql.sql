--
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
--

CREATE TABLE IF NOT EXISTS nessy_document (
  kind        TEXT             NOT NULL,
  key         TEXT COLLATE "C" NOT NULL,
  payload     BYTEA            NOT NULL,
  version     BIGINT           NOT NULL,
  updated_at  TIMESTAMPTZ      NOT NULL,
  PRIMARY KEY (kind, key)
);

CREATE TABLE IF NOT EXISTS nessy_journal (
  kind         TEXT             NOT NULL,
  key          TEXT COLLATE "C" NOT NULL,
  seq          BIGINT           NOT NULL,
  payload      BYTEA            NOT NULL,
  appended_at  TIMESTAMPTZ      NOT NULL,
  PRIMARY KEY (kind, key, seq)
);
