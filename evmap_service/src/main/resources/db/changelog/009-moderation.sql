--liquibase formatted sql

--changeset evmap:009 dbms:postgresql
-- Reporting comments and blocking their authors (App Store guideline 1.2, ADR 0020). Both are
-- user_data owned by the account that acts, and both go when that account goes.

-- One report per reporter and comment. Comment text is not copied here: an admin reads the comment
-- itself, and removing the comment removes its reports with it. A dismissed report stays, so the
-- comment does not come back into the queue and the reporter keeps it hidden.
CREATE TABLE user_data.comment_report
(
    id          UUID PRIMARY KEY,
    comment_id  UUID        NOT NULL REFERENCES user_data.station_comment (id) ON DELETE CASCADE,
    reporter_id UUID        NOT NULL REFERENCES user_data.account (id) ON DELETE CASCADE,
    reason      VARCHAR(16) NOT NULL CHECK (reason IN ('spam', 'offensive', 'wrong', 'other')),
    status      VARCHAR(16) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'dismissed')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES user_data.account (id) ON DELETE SET NULL,
    UNIQUE (comment_id, reporter_id)
);
CREATE INDEX comment_report_open_idx ON user_data.comment_report (created_at) WHERE status = 'open';
CREATE INDEX comment_report_reporter_idx ON user_data.comment_report (reporter_id);

-- The row has its own id so the account area can list and lift a block without ever learning whom it
-- concerns: comments show no author, and the API never hands out another account's id.
CREATE TABLE user_data.account_block
(
    id         UUID PRIMARY KEY,
    blocker_id UUID        NOT NULL REFERENCES user_data.account (id) ON DELETE CASCADE,
    blocked_id UUID        NOT NULL REFERENCES user_data.account (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);
CREATE INDEX account_block_blocked_idx ON user_data.account_block (blocked_id);

--rollback DROP TABLE user_data.account_block;
--rollback DROP TABLE user_data.comment_report;
