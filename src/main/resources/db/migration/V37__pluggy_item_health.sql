-- V37 — a saúde da conexão bancária deixa de ser invisível.
--
-- O DEFEITO, com data e número. Em 17/09/2026 a conta do dono mostrava saldo
-- certo (R$ 250,13, igual ao painel do Pluggy) e, ao mesmo tempo, a fatura de
-- setembro de DOIS cartões com total R$ 0,00 e pagamento cheio. A causa: as
-- compras dos cartões pararam em 05/09 — nove lançamentos, R$ 373,66 — porque
-- o Pluggy não coletou nada novo daqueles itens desde 14/08. O nosso sync
-- respondia HTTP 200, `itemsSynced: 5`, `transactionsImported: 0`, e ninguém
-- tinha como saber que aquilo era dado velho: a tabela guardava apenas
-- `last_synced_at`, que é quando NÓS lemos, não quando o BANCO foi lido.
--
-- Um aplicativo de finanças que mostra saldo velho sem avisar é pior do que um
-- que não mostra nada: quem confia no número decide em cima dele.
--
-- As colunas abaixo separam três tempos que estavam colapsados em um:
--   last_synced_at        quando nós lemos o Pluggy        (já existia)
--   provider_updated_at   quando o Pluggy leu o BANCO      (novo)
--   update_requested_at   quando pedimos uma coleta nova   (novo)
--
-- E o estado que o provedor declara, para a interface poder dizer "precisa
-- reconectar" em vez de mentir por omissão.
ALTER TABLE pluggy_items
    ADD COLUMN status VARCHAR(40),
    ADD COLUMN execution_status VARCHAR(60),
    ADD COLUMN status_detail VARCHAR(400),
    ADD COLUMN provider_updated_at TIMESTAMPTZ,
    ADD COLUMN update_requested_at TIMESTAMPTZ;

-- Nulo é a resposta honesta para as conexões que já existem: nunca perguntamos
-- o estado delas, então não há o que preencher. O primeiro sync depois desta
-- migration grava o real. Nada de default otimista aqui — inventar "UPDATED"
-- para linha antiga seria recriar exatamente o problema que a migration fecha.

COMMENT ON COLUMN pluggy_items.status IS
    'Estado do item no Pluggy (UPDATED, LOGIN_ERROR, WAITING_USER_INPUT, OUTDATED...). Nulo = nunca consultado.';
COMMENT ON COLUMN pluggy_items.execution_status IS
    'Detalhe da ultima execucao do Pluggy (SUCCESS, USER_INPUT_TIMEOUT, INVALID_CREDENTIALS...).';
COMMENT ON COLUMN pluggy_items.provider_updated_at IS
    'Quando o Pluggy coletou do banco. E a idade real do dado — nao confundir com last_synced_at.';
COMMENT ON COLUMN pluggy_items.update_requested_at IS
    'Quando pedimos ao Pluggy uma coleta nova (PATCH /items/{id}), para nao pedir em rajada.';
