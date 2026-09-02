-- EC-149: GRUPO FAMILIAR — dois logins, uma casa, e cada um decide o que a casa vê.
--
-- O pedido do dono: "eu e minha mulher moramos juntos e gostaríamos de
-- visualizar os gastos em conjunto, mas sem confundir os extratos". A visão
-- compartilhada não é um extrato fundido: cada pessoa continua dona das suas
-- linhas, e o que atravessa para a casa é decidido POR QUEM COMPARTILHA, linha
-- por linha, na consulta — nunca filtrado depois, em memória.
--
-- Um usuário pertence a NO MÁXIMO UM grupo (v1). É o UNIQUE em
-- family_members.user_id, e simplifica tudo o que vem depois: autorização,
-- linguagem ("a Casa") e a tela. Um segundo grupo por pessoa exigiria eleger
-- "qual casa" em cada rota, e ninguém pediu isso.

CREATE TABLE family_groups (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    -- "Casa" por padrão: o nome existe para a tela, não para distinguir grupos
    name VARCHAR(60) NOT NULL DEFAULT 'Casa',
    -- Quem criou. Apagar o usuário apaga a casa (e, em cascata, tudo dela):
    -- um grupo sem dono não teria quem pudesse convidar ou desfazer.
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_family_groups_owner ON family_groups (owner_user_id);

-- O membro CARREGA os parâmetros do que ELE compartilha. Ficam aqui, e não no
-- grupo, porque são decisão individual: ela pode mostrar tudo e ele só os
-- totais, na mesma casa.
CREATE TABLE family_members (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    group_id UUID NOT NULL REFERENCES family_groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- OWNER (criou a casa) ou MEMBER (entrou por convite)
    role VARCHAR(8) NOT NULL,
    -- NONE (não mostro nada) · TOTALS (só somas por categoria e do período,
    -- nenhuma linha) · TRANSACTIONS (mostro as linhas). Padrão TOTALS: entrar
    -- numa casa e não mostrar nada seria estranho, mas abrir as linhas é um
    -- passo a mais que a pessoa dá quando quiser.
    share_scope VARCHAR(16) NOT NULL DEFAULT 'TOTALS',
    -- Quando o membro restringe a lista de contas (tabela abaixo), as linhas
    -- SEM conta (upload manual de arquivo, account_id nulo) só entram se ele
    -- marcar este pseudo-item "extrato importado". TRUE por padrão porque, com
    -- a lista vazia (todas as contas), o esperado é que tudo entre.
    include_unassigned BOOLEAN NOT NULL DEFAULT TRUE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- um grupo por usuário (v1): é a regra do modelo, gravada onde não se
    -- contorna por uma segunda requisição concorrente
    CONSTRAINT uq_family_members_user UNIQUE (user_id),
    CONSTRAINT ck_family_members_role CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT ck_family_members_scope CHECK (share_scope IN ('NONE', 'TOTALS', 'TRANSACTIONS'))
);

-- A listagem de membros é a única consulta que varre a tabela, sempre pela
-- casa; a busca "de qual casa este usuário é" já sai pelo UNIQUE acima.
CREATE INDEX idx_family_members_group ON family_members (group_id);

-- Categorias que o membro TIRA da visão da casa: saem das linhas E das somas,
-- e o total dele na casa é recalculado sem elas — senão o outro deduziria o
-- valor escondido pela diferença. ON DELETE CASCADE nos dois lados: sem o
-- membro não há o que ocultar, e sem a categoria os lançamentos dela já
-- perderam o category_id (V6, SET NULL) — não sobra nada a que a regra se
-- refira.
CREATE TABLE family_member_hidden_categories (
    member_id UUID NOT NULL REFERENCES family_members(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (member_id, category_id)
);

-- Contas (connector_accounts) que o membro mostra. Lista VAZIA = todas as
-- contas; preenchida = só estas. Sem FK para connector_accounts, de propósito:
-- com ON DELETE CASCADE, apagar a última conta listada esvaziaria a lista e
-- "só esta conta" viraria "todas" em silêncio — o único sentido em que a regra
-- não pode falhar. Um id que deixou de existir simplesmente não casa com linha
-- nenhuma, que é o lado seguro. O dono da conta é validado na escrita
-- (PUT /family/sharing), então id alheio não entra por aqui.
CREATE TABLE family_member_shared_accounts (
    member_id UUID NOT NULL REFERENCES family_members(id) ON DELETE CASCADE,
    account_id UUID NOT NULL,
    PRIMARY KEY (member_id, account_id)
);

-- Convite por CÓDIGO, não por e-mail: MAIL_* não existe no ambiente de deploy e
-- casal troca código pelo WhatsApp em segundos. Como em password_reset_tokens
-- (V11), só o SHA-256 do código é gravado — vazar o banco não deixa ninguém
-- entrar numa casa. Validade de 7 dias, uso único (used_at), e um convite vivo
-- por grupo de cada vez: emitir de novo apaga o anterior.
CREATE TABLE family_invites (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    group_id UUID NOT NULL REFERENCES family_groups(id) ON DELETE CASCADE,
    -- só o OWNER emite; guardado para o histórico dizer quem convidou
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_family_invites_group ON family_invites (group_id);
