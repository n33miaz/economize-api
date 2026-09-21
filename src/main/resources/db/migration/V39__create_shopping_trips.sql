-- V39 — CARRINHO DE COMPRAS: a compra registrada enquanto acontece, no mercado,
-- sem internet, e compartilhada com a casa quando a rede volta.
--
-- O pedido do dono (20/09/2026): "enquanto eu faço a compra eu vou tirando
-- foto dos preços e dizendo quantas unidades eu peguei", para saber quanto
-- está gastando ANTES do caixa, conferir depois contra a nota, e no fim
-- comparar o preço de cada item com o que ele custou da outra vez, no outro
-- mercado.
--
-- Três decisões de modelo, e o porquê de cada uma:
--
-- 1. O ID DE VERDADE NASCE NO APARELHO. `client_id` é a chave que o app gera
--    offline e reenvia quantas vezes precisar: o PUT é idempotente por ele, e
--    uma sincronização repetida (rede caindo no meio) não duplica viagem nem
--    item. O `id` do servidor existe para as chaves estrangeiras.
--
-- 2. ITEM NÃO SE APAGA, SE MARCA (`deleted`). Dois aparelhos da mesma casa
--    editam a mesma lista sem se ver. Se apagar fosse DELETE, o outro aparelho
--    reenviaria o item que ainda tem e ele ressuscitaria. A lápide (tombstone)
--    viaja na resposta e o outro lado apaga também. `client_updated_at` é o
--    relógio do aparelho que fez a última edição: na fusão, o mais novo vence
--    (last-write-wins), campo a campo não — item a item, que é o que dá para
--    explicar para uma pessoa.
--
-- 3. O NOME NORMALIZADO É A CHAVE DO HISTÓRICO DE PREÇOS. "Arroz Tio João 5kg"
--    e "arroz tio joao 5KG" são o mesmo produto; sem acento, minúsculo e com
--    espaços colapsados eles caem na mesma chave, e o "quanto custou da última
--    vez" sai de uma consulta só.

CREATE TABLE shopping_trips (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    -- Quem abriu a viagem. Apagar a conta leva a lista junto.
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Preenchido = a casa inteira vê e edita. SET NULL, não CASCADE: desfazer a
    -- casa não pode apagar a compra de alguém — ela volta a ser só do dono.
    family_group_id UUID REFERENCES family_groups(id) ON DELETE SET NULL,
    client_id VARCHAR(64) NOT NULL,
    store_name VARCHAR(120),
    -- OPEN (no mercado) → CLOSED (passou no caixa) → RECONCILED (casou com
    -- um lançamento do extrato).
    status VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    -- O teto que a pessoa se deu antes de entrar. Nulo = sem teto.
    budget NUMERIC(19, 4),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ,
    -- O total da NOTA, informado ou lido depois. É o número oficial; a soma
    -- dos itens é a estimativa feita no corredor.
    receipt_total NUMERIC(19, 4),
    -- O lançamento do extrato que pagou esta compra. SET NULL: reimportar o
    -- extrato pode apagar a linha, e a viagem não pode sumir com ela.
    reconciled_transaction_id UUID REFERENCES bank_transactions(id) ON DELETE SET NULL,
    notes VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A idempotência offline gravada onde uma segunda requisição concorrente
    -- não a contorna: o mesmo aparelho reenviando a mesma viagem casa aqui.
    CONSTRAINT uq_shopping_trips_owner_client UNIQUE (user_id, client_id),
    CONSTRAINT ck_shopping_trips_status CHECK (status IN ('OPEN', 'CLOSED', 'RECONCILED'))
);

-- A listagem é sempre "as minhas" ou "as da casa", da mais recente para a
-- mais antiga — os dois índices são essas duas consultas.
CREATE INDEX idx_shopping_trips_user_started ON shopping_trips (user_id, started_at DESC);
CREATE INDEX idx_shopping_trips_family_started ON shopping_trips (family_group_id, started_at DESC)
    WHERE family_group_id IS NOT NULL;

CREATE TABLE shopping_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    trip_id UUID NOT NULL REFERENCES shopping_trips(id) ON DELETE CASCADE,
    client_id VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    -- minúsculo, sem acento, espaços colapsados — a chave do histórico de preços
    normalized_name VARCHAR(120) NOT NULL,
    -- três casas porque balança pesa em gramas: 0,750 kg de queijo
    quantity NUMERIC(10, 3) NOT NULL DEFAULT 1,
    -- Nulo enquanto a pessoa só pegou e ainda não anotou o preço
    unit_price NUMERIC(19, 4),
    -- "leve 3 pague 2", "10% no app" — a promoção como estava escrita na gôndola
    promo_note VARCHAR(200),
    -- TRUE = pegou (entra no total). FALSE = viu o preço e deixou na prateleira:
    -- fica registrado para o histórico, mas não soma.
    checked BOOLEAN NOT NULL DEFAULT TRUE,
    -- Referência LOCAL da foto no aparelho (v1). A imagem não sobe: a nota
    -- fiscal já é a prova, e a foto serve à pessoa no corredor, não ao servidor.
    photo_ref VARCHAR(200),
    -- Quem colocou o item no carrinho — na casa, isso responde "quem pegou
    -- isso?". SET NULL: a pessoa sair da casa (ou da conta) não apaga o item
    -- da compra que já aconteceu.
    added_by UUID REFERENCES users(id) ON DELETE SET NULL,
    -- Lápide para sincronizar remoção entre aparelhos (ver cabeçalho)
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    -- O relógio do aparelho na última edição: quem decide a fusão
    client_updated_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_shopping_items_trip_client UNIQUE (trip_id, client_id)
);

CREATE INDEX idx_shopping_items_trip ON shopping_items (trip_id);
-- O histórico de preços: "este produto, as últimas vezes" — sem este índice a
-- consulta varreria os itens de todas as compras de todo mundo.
CREATE INDEX idx_shopping_items_normalized ON shopping_items (normalized_name, updated_at DESC);

COMMENT ON TABLE shopping_trips IS
    'Uma ida ao mercado: registrada offline no aparelho (client_id) e fundida no servidor por idempotencia.';
COMMENT ON TABLE shopping_items IS
    'Um item do carrinho. deleted e uma lapide para sincronizar remocao; client_updated_at decide a fusao (last-write-wins).';
COMMENT ON COLUMN shopping_items.normalized_name IS
    'Nome minusculo, sem acento e com espacos colapsados: a chave do historico de precos.';
