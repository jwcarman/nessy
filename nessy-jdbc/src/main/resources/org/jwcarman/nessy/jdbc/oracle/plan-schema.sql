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

-- Oracle — see schema.sql's header.

CREATE TABLE IF NOT EXISTS nessy_plan (
  conversation_id varchar2(255)  NOT NULL,
  ordinal         number(10)     NOT NULL,
  title           varchar2(1024) NOT NULL,
  status          varchar2(16)   NOT NULL,
  PRIMARY KEY (conversation_id, ordinal)
);
