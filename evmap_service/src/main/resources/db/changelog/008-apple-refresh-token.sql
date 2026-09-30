--liquibase formatted sql

--changeset evmap:008 dbms:postgresql
-- Sign in with Apple requires revoking the user's token when the account is deleted (App Store
-- guideline 5.1.1(v)), and Apple only revokes a refresh token. It is kept on the identity it belongs
-- to, AES-GCM encrypted by the API (TokenCipher, key from the environment), together with the client
-- id it was issued to: the bundle id for the native flow, the Services ID for the web flow, and the
-- revoke call must name the same one (ADR 0020).
--
-- A credential, not the user's data: it is deleted with the identity and never part of the data export.
ALTER TABLE user_data.provider_identity
    ADD COLUMN refresh_token           TEXT,
    ADD COLUMN refresh_token_client_id VARCHAR(255);

--rollback ALTER TABLE user_data.provider_identity DROP COLUMN refresh_token_client_id, DROP COLUMN refresh_token;
