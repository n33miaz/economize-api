-- EC-135/140/141: as três peças de "quanto entra, quanto vale minha hora e o
-- que eu quero comprar". Nascem juntas porque uma não serve sem a outra: o
-- desejo em horas depende da jornada, e a jornada só vira dinheiro por hora
-- com a renda declarada.

-- EC-135: cada fonte de renda tem o PRÓPRIO calendário. O VR cai dia 25 e o
-- salário dia 5; tratar as duas pela mesma âncora é o que fazia o gasto do VR
-- ser cobrado do mês que estava fechando em vez do que ia abrir. A âncora mora
-- aqui, por fonte, e não no perfil do usuário.
CREATE TABLE income_sources (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- SALARY / MEAL_VOUCHER (VR) / FOOD_VOUCHER (VA) / ADVANCE / OTHER
    kind VARCHAR(16) NOT NULL,
    name VARCHAR(120) NOT NULL,
    expected_amount NUMERIC(19, 4),
    anchor_day SMALLINT,
    -- o motor de recorrência (EC-095) propõe a fonte a partir do extrato, mas
    -- proposta não é verdade: só o usuário confirma quanto ganha. Enquanto
    -- confirmed=false o valor aparece como sugestão e nunca alimenta o cálculo
    -- de desejo — errar a hora de trabalho por um salário chutado é pior do
    -- que não calcular
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    -- de onde veio a sugestão; ON DELETE SET NULL porque descartar a série
    -- detectada não pode apagar a fonte que o usuário já confirmou
    series_id UUID REFERENCES recurring_series(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- o mesmo tipo pode repetir (dois empregos), o mesmo nome dentro do tipo não
    CONSTRAINT uq_income_sources_user_kind_name UNIQUE (user_id, kind, name)
);

CREATE INDEX idx_income_sources_user ON income_sources (user_id);
CREATE INDEX idx_income_sources_user_active ON income_sources (user_id, active);

-- EC-141: a jornada é 1:1 com o usuário — daí a PK ser a própria FK. Sem linha
-- aqui, o custo em horas é DESCONHECIDO e a API devolve null; jornada padrão
-- de 8h/5d inventaria uma hora que não é a da pessoa.
CREATE TABLE work_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    days_per_week SMALLINT NOT NULL,
    -- NUMERIC porque meia hora existe (6h30 = 6.50) e SMALLINT arredondaria
    hours_per_day NUMERIC(4, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_work_profiles_days CHECK (days_per_week BETWEEN 1 AND 7),
    CONSTRAINT ck_work_profiles_hours CHECK (hours_per_day > 0 AND hours_per_day <= 24)
);

-- EC-140: o desejo. Vira meta e a meta vira compra — por isso status e não uma
-- tabela separada de metas: é o mesmo objeto amadurecendo, e mover de tabela
-- perderia o histórico de quando a pessoa quis.
CREATE TABLE wishes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    target_amount NUMERIC(19, 4) NOT NULL,
    saved_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    category_id UUID REFERENCES categories(id) ON DELETE SET NULL,
    -- WISH (só quero) / GOAL (estou guardando) / PURCHASED / ARCHIVED
    status VARCHAR(12) NOT NULL DEFAULT 'WISH',
    -- data que o usuário QUER, não a que o app projeta; a projeção é calculada
    target_date DATE,
    note VARCHAR(400),
    purchased_at DATE,
    -- transação do extrato que confirmou a compra: fecha o ciclo desejo→compra
    purchase_transaction_id UUID REFERENCES bank_transactions(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_wishes_target_positive CHECK (target_amount > 0),
    CONSTRAINT ck_wishes_saved_not_negative CHECK (saved_amount >= 0)
);

CREATE INDEX idx_wishes_user ON wishes (user_id);
CREATE INDEX idx_wishes_user_status ON wishes (user_id, status);
