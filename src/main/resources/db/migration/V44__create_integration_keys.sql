-- CHAVES DE INTEGRAÇÃO: a credencial que OUTRO SISTEMA do dono usa para ler
-- o Economize, sem carregar a sessão dele.
--
-- POR QUE EXISTE. O carrinho de compras vai ser refeito como módulo de outro
-- sistema do dono, e a decisão foi de mão única: é o outro sistema que chama o
-- Economize, com uma chave do dono; o Economize nunca chama de volta. O JWT de
-- sessão não serve para isso — vence em horas, nasce de senha + segundo fator
-- e dá acesso à conta inteira.
--
-- O QUE A CHAVE PODE. Só as rotas `/api/v1/integration/**`, que são leituras.
-- Ela não vale em nenhuma outra rota, e o JWT não vale nelas: uma chave vazada
-- não lê extrato nem mexe na conta.
--
-- O SEGREDO NÃO É GRAVADO. Fica só o SHA-256 dele, em hexadecimal. SHA-256
-- puro (e não bcrypt) porque o segredo são 32 bytes aleatórios: não há
-- dicionário a tentar, e a conferência acontece a cada chamada — um hash lento
-- só custaria CPU. `prefix` guarda o começo do segredo para a pessoa
-- reconhecer na lista qual chave é qual.
CREATE TABLE integration_keys (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    -- Apagar a conta leva as chaves junto: chave sem dono não autentica ninguém.
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- O nome que a pessoa deu ("UniOver"): é por ele que ela sabe o que revogar.
    name VARCHAR(60) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    prefix VARCHAR(12) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Atualizado no máximo uma vez a cada poucos minutos, não a cada chamada.
    last_used_at TIMESTAMPTZ,
    -- Revogar não apaga a linha: a lista continua mostrando que a chave
    -- existiu e quando deixou de valer.
    revoked_at TIMESTAMPTZ,
    -- É por aqui que a autenticação acha a chave; único porque dois segredos
    -- com o mesmo hash seriam a mesma credencial para dois donos.
    CONSTRAINT uq_integration_keys_hash UNIQUE (key_hash)
);

CREATE INDEX idx_integration_keys_user ON integration_keys (user_id, created_at DESC);
