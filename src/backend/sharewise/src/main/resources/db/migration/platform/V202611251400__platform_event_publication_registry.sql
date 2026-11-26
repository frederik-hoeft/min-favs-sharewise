-- Spring Modulith event publication registry (P2.4a): shared delivery infrastructure,
-- not business data of any bounded context. The table and indexes are the current
-- (non-legacy) Spring Modulith 2.2 PostgreSQL schema; framework schema initialization is
-- disabled, so upgrades to this structure are reviewed Flyway migrations.
CREATE SCHEMA messaging;

CREATE TABLE messaging.event_publication (
    id                     uuid NOT NULL,
    listener_id            text NOT NULL,
    event_type             text NOT NULL,
    serialized_event       text NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx
    ON messaging.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx
    ON messaging.event_publication (completion_date);

-- The runtime role records, updates and deletes publications; it cannot change the structure.
GRANT USAGE ON SCHEMA messaging TO sharewise_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON messaging.event_publication TO sharewise_app;
