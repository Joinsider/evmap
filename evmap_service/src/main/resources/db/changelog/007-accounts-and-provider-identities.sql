--liquibase formatted sql

--changeset evmap:007 dbms:postgresql
-- Splits the sign-in record into the internal account and the provider identities attached to it
-- (ADR 0018). Until now one row was both: user_identity held Apple's subject and its id was what
-- every other user_data row and the access token referred to. With Google and GitHub one person can
-- have several provider identities, so the thing the rest of the system points at becomes the
-- account.
--
-- Every existing identity gets an account with the SAME uuid. Comments and every access token already
-- issued therefore keep pointing at the same person without being rewritten.
CREATE TABLE user_data.account
(
    id            UUID PRIMARY KEY,
    -- Set only by a manual UPDATE in the database. There is deliberately no API that writes it.
    is_admin      BOOLEAN     NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO user_data.account (id, created_at, last_login_at)
SELECT id, created_at, last_login_at
FROM user_data.user_identity;

ALTER TABLE user_data.user_identity
    RENAME TO provider_identity;
ALTER TABLE user_data.provider_identity
    RENAME CONSTRAINT user_identity_pkey TO provider_identity_pkey;
ALTER TABLE user_data.provider_identity
    RENAME CONSTRAINT user_identity_provider_provider_subject_key TO provider_identity_provider_provider_subject_key;

ALTER TABLE user_data.provider_identity
    ADD COLUMN account_id UUID REFERENCES user_data.account (id) ON DELETE CASCADE;
UPDATE user_data.provider_identity
SET account_id = id;
ALTER TABLE user_data.provider_identity
    ALTER COLUMN account_id SET NOT NULL;
CREATE INDEX provider_identity_account_idx ON user_data.provider_identity (account_id);

-- Personal data (docs/privacy/data-processing.md): kept to link accounts and, from phase 2, for data
-- export. email_verified is the provider's own claim; only a verified address may link two identities,
-- otherwise registering somebody else's address at any provider would take over their account.
ALTER TABLE user_data.provider_identity
    ADD COLUMN email VARCHAR(320);
ALTER TABLE user_data.provider_identity
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX provider_identity_verified_email_idx ON user_data.provider_identity (lower(email)) WHERE email_verified;

-- A comment belongs to the person, not to the provider they happened to sign in with.
ALTER TABLE user_data.station_comment
    DROP CONSTRAINT station_comment_user_identity_id_fkey;
ALTER TABLE user_data.station_comment
    RENAME COLUMN user_identity_id TO account_id;
ALTER TABLE user_data.station_comment
    ADD CONSTRAINT station_comment_account_id_fkey FOREIGN KEY (account_id) REFERENCES user_data.account (id) ON DELETE CASCADE;
ALTER INDEX user_data.station_comment_user_idx RENAME TO station_comment_account_idx;

--rollback ALTER INDEX user_data.station_comment_account_idx RENAME TO station_comment_user_idx;
--rollback ALTER TABLE user_data.station_comment DROP CONSTRAINT station_comment_account_id_fkey;
--rollback ALTER TABLE user_data.station_comment RENAME COLUMN account_id TO user_identity_id;
--rollback DELETE FROM user_data.provider_identity WHERE id <> account_id;
--rollback ALTER TABLE user_data.provider_identity DROP COLUMN email_verified, DROP COLUMN email, DROP COLUMN account_id;
--rollback ALTER TABLE user_data.provider_identity RENAME CONSTRAINT provider_identity_provider_provider_subject_key TO user_identity_provider_provider_subject_key;
--rollback ALTER TABLE user_data.provider_identity RENAME CONSTRAINT provider_identity_pkey TO user_identity_pkey;
--rollback ALTER TABLE user_data.provider_identity RENAME TO user_identity;
--rollback ALTER TABLE user_data.station_comment ADD CONSTRAINT station_comment_user_identity_id_fkey FOREIGN KEY (user_identity_id) REFERENCES user_data.user_identity (id) ON DELETE CASCADE;
--rollback DROP TABLE user_data.account;
