-- =====================================================================
--  IDEAQR Digital Gateway — V5: Spring Session JDBC tables (audit 3.7)
--
--  The standard Spring Session schema (SPRING_SESSION + SPRING_SESSION_ATTRIBUTES).
--  HTTP sessions now persist in the shared database instead of per-node Tomcat
--  memory, which is what lets the app scale horizontally and survive a restart
--  without logging everyone out.
--
--  Flyway — not Spring Session's own initializer — owns this DDL, so it is created
--  exactly once and in the same versioned, validated stream as the rest of the
--  schema (spring.session.jdbc.initialize-schema=never).
--
--  Portability: the schema is identical across H2 and PostgreSQL except for the
--  binary attribute column, whose type is injected via a Flyway placeholder
--  (`session_blob_type`): BINARY LARGE OBJECT on H2 (default/test/prod),
--  BYTEA on the postgres profile. See application*.properties.
-- =====================================================================

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT          NOT NULL,
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)                NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200)            NOT NULL,
    ATTRIBUTE_BYTES    ${session_blob_type}    NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
