-- EC-106: itens do Pluggy vinculados ao usuário (absorve o EC-072).
-- Até aqui as conexões eram globais por variável de ambiente (PLUGGY_ITEM_IDS)
-- e só o PLUGGY_OWNER_EMAIL podia sincronizar. Esta tabela torna cada conexão
-- (item) propriedade de uma conta: o widget Pluggy Connect cria o item e a API
-- registra o vínculo aqui. As credenciais da aplicação (clientId/clientSecret)
-- continuam no ambiente — o que é do usuário é o item, nunca o segredo.
CREATE TABLE pluggy_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- id do item na API do Pluggy (UUID deles, guardado como texto para não
    -- acoplar o schema ao formato de id de um terceiro)
    item_id VARCHAR(64) NOT NULL,
    -- identificação da instituição conectada, copiada do connector do item na
    -- hora do registro: a listagem do app não precisa chamar o Pluggy
    connector_id BIGINT,
    connector_name VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_synced_at TIMESTAMPTZ,
    -- um item pertence a UMA conta: o registro de um itemId já vinculado (pelo
    -- próprio usuário ou por outro) responde conflito, nunca reatribui
    CONSTRAINT uq_pluggy_items_item_id UNIQUE (item_id)
);

CREATE INDEX idx_pluggy_items_user ON pluggy_items (user_id);
