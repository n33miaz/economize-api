-- EC-095: detecção de gastos/receitas recorrentes — fundação do saldo previsto.
-- A série guarda o que o motor aprendeu sobre cada entidade (merchant_key):
-- cadência, dia âncora do mês, tolerância e tipo de valor. O vínculo com as
-- transações fica em tabela própria porque bank_transactions pertence a outro
-- ticket e não pode ser alterada aqui.
CREATE TABLE recurring_series (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    merchant_key VARCHAR(160) NOT NULL,
    display_name VARCHAR(160),
    category_id UUID REFERENCES categories(id) ON DELETE SET NULL,
    flow VARCHAR(10) NOT NULL,        -- EXPENSE / INCOME / INTERNAL
    cadence VARCHAR(12) NOT NULL,     -- MONTHLY / WEEKLY / QUARTERLY / IRREGULAR
    anchor_day SMALLINT,              -- dia do mês típico (NULL fora de MONTHLY/QUARTERLY)
    day_tolerance SMALLINT,
    amount_type VARCHAR(10) NOT NULL, -- FIXED (assinatura/plano) / VARIABLE (conta de consumo)
    expected_amount NUMERIC(19, 4),
    occurrences INTEGER NOT NULL DEFAULT 0,
    first_seen_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    -- descarte explícito do usuário (DELETE de série detectada): a varredura
    -- nunca reativa uma série dismissed — só um PATCH active=true desfaz
    dismissed BOOLEAN NOT NULL DEFAULT FALSE,
    source VARCHAR(10) NOT NULL,      -- DETECTED / USER
    -- vigência do agendamento manual (source=USER): a previsão de saldo não
    -- projeta antes de starts_at nem depois de ends_at (parcelamento/contrato
    -- com fim). Séries detectadas ficam com NULL — a vigência delas é o próprio
    -- histórico de ocorrências.
    starts_at DATE,
    ends_at DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- a detecção é re-executável: uma série por entidade e fluxo, nunca duplica
    CONSTRAINT uq_recurring_series_user_key_flow UNIQUE (user_id, merchant_key, flow)
);

CREATE INDEX idx_recurring_series_user ON recurring_series (user_id);
CREATE INDEX idx_recurring_series_user_active ON recurring_series (user_id, active);

-- Cada transação pertence a no máximo uma série (UNIQUE em bank_transaction_id):
-- é essa restrição que torna a re-execução idempotente sem varredura manual.
CREATE TABLE recurring_series_links (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    series_id UUID NOT NULL REFERENCES recurring_series(id) ON DELETE CASCADE,
    bank_transaction_id UUID NOT NULL UNIQUE REFERENCES bank_transactions(id) ON DELETE CASCADE,
    matched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_recurring_series_links_series ON recurring_series_links (series_id);
