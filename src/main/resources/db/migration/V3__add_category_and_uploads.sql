-- EC-020: categorização de transações bancárias
ALTER TABLE bank_transactions
    ADD COLUMN IF NOT EXISTS category VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_bank_transactions_category
    ON bank_transactions(category);

-- EC-024: idempotência no upload de extratos via SHA-256
CREATE TABLE IF NOT EXISTS statement_uploads (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    file_hash CHAR(64) NOT NULL,
    file_name VARCHAR(255),
    format VARCHAR(16) NOT NULL,
    transactions_imported INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_uploads_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_user_hash UNIQUE (user_id, file_hash)
);

CREATE INDEX IF NOT EXISTS idx_statement_uploads_user ON statement_uploads(user_id);
