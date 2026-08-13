-- Categorias deixam de ser enum em código e viram entidade: o usuário pode criar
-- as próprias (com grupo/tipo/cor/ícone) e as 11 do sistema viram seeds globais
-- (user_id NULL) ligadas ao enum legado via system_key para backfill e fallback.
CREATE TABLE categories (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    slug VARCHAR(60) NOT NULL,
    group_name VARCHAR(60),
    flow VARCHAR(10) NOT NULL DEFAULT 'EXPENSE',
    color VARCHAR(9),
    icon VARCHAR(40),
    system_key VARCHAR(32),
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Unicidade separada para seeds globais e categorias por usuário: NULL não
-- participa de UNIQUE composto no Postgres, então usamos índices parciais.
CREATE UNIQUE INDEX uq_categories_system_slug ON categories (slug) WHERE user_id IS NULL;
CREATE UNIQUE INDEX uq_categories_user_slug ON categories (user_id, slug) WHERE user_id IS NOT NULL;
CREATE INDEX idx_categories_user ON categories (user_id);

-- Padrões aprendidos: cada correção/confirmação do usuário vira uma regra
-- (descrição normalizada -> categoria) que tem precedência sobre as keywords
-- do sistema nas próximas importações. hits mede a utilidade da regra.
CREATE TABLE category_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    -- mesmo tamanho de bank_transactions.normalized_description: o padrão
    -- aprendido É a chave normalizada, os limites precisam andar juntos
    pattern VARCHAR(160) NOT NULL,
    match_type VARCHAR(12) NOT NULL DEFAULT 'CONTAINS',
    origin VARCHAR(12) NOT NULL,
    hits INTEGER NOT NULL DEFAULT 0,
    last_hit_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_rules_user_pattern UNIQUE (user_id, pattern)
);

CREATE INDEX idx_category_rules_user ON category_rules (user_id);

-- Transações ganham o ciclo de revisão: o motor sugere (SUGGESTED) ou pede ajuda
-- (UNCATEGORIZED) e só a aprovação do usuário promove a CONFIRMED. upload_id dá a
-- rastreabilidade arquivo -> transações que a tela de revisão pós-import precisa.
ALTER TABLE bank_transactions
    ADD COLUMN category_id UUID REFERENCES categories(id) ON DELETE SET NULL,
    ADD COLUMN review_status VARCHAR(14) NOT NULL DEFAULT 'CONFIRMED',
    ADD COLUMN categorized_by VARCHAR(12),
    ADD COLUMN confidence NUMERIC(3,2),
    ADD COLUMN normalized_description VARCHAR(160),
    ADD COLUMN upload_id UUID REFERENCES statement_uploads(id) ON DELETE SET NULL;

CREATE INDEX idx_bank_tx_user_status ON bank_transactions (user_id, review_status);
CREATE INDEX idx_bank_tx_user_category ON bank_transactions (user_id, category_id);
CREATE INDEX idx_bank_tx_user_date ON bank_transactions (user_id, date);
CREATE INDEX idx_bank_tx_upload ON bank_transactions (upload_id);

-- Seeds com UUID fixo: referências estáveis entre ambientes e nos clientes.
-- Cores ficam NULL de propósito — o design system dos clientes resolve a cor
-- pelo system_key com a paleta de gráficos validada (03_DESIGN_SYSTEM §2.1).
INSERT INTO categories (id, user_id, name, slug, group_name, flow, icon, system_key) VALUES
    ('c0000000-0000-4000-8000-000000000001', NULL, 'Alimentação',    'alimentacao',    'Essenciais',      'EXPENSE', 'utensils',         'FOOD'),
    ('c0000000-0000-4000-8000-000000000002', NULL, 'Transporte',     'transporte',     'Essenciais',      'EXPENSE', 'car',              'TRANSPORT'),
    ('c0000000-0000-4000-8000-000000000003', NULL, 'Lazer',          'lazer',          'Estilo de vida',  'EXPENSE', 'gamepad-2',        'LEISURE'),
    ('c0000000-0000-4000-8000-000000000004', NULL, 'Saúde',          'saude',          'Essenciais',      'EXPENSE', 'heart-pulse',      'HEALTH'),
    ('c0000000-0000-4000-8000-000000000005', NULL, 'Educação',       'educacao',       'Estilo de vida',  'EXPENSE', 'graduation-cap',   'EDUCATION'),
    ('c0000000-0000-4000-8000-000000000006', NULL, 'Moradia',        'moradia',        'Essenciais',      'EXPENSE', 'house',            'HOUSING'),
    ('c0000000-0000-4000-8000-000000000007', NULL, 'Compras',        'compras',        'Estilo de vida',  'EXPENSE', 'shopping-bag',     'SHOPPING'),
    ('c0000000-0000-4000-8000-000000000008', NULL, 'Contas e serviços', 'contas-e-servicos', 'Essenciais', 'EXPENSE', 'plug-zap',        'UTILITIES'),
    ('c0000000-0000-4000-8000-000000000009', NULL, 'Receitas',       'receitas',       'Movimentações',   'INCOME',  'banknote',         'INCOME'),
    ('c0000000-0000-4000-8000-000000000010', NULL, 'Transferências', 'transferencias', 'Movimentações',   'BOTH',    'arrow-left-right', 'TRANSFER'),
    ('c0000000-0000-4000-8000-000000000011', NULL, 'Outros',         'outros',         'Movimentações',   'BOTH',    'circle-ellipsis',  'OTHER');

-- Backfill: transações antigas guardavam o enum como string em category; liga o
-- category_id ao seed correspondente. Elas já nascem CONFIRMED (default) para
-- não inundar a fila de revisão com histórico pré-feature.
UPDATE bank_transactions bt
SET category_id = c.id
FROM categories c
WHERE c.user_id IS NULL
  AND c.system_key = bt.category
  AND bt.category_id IS NULL;
