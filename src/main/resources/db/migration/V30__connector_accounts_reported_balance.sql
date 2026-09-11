-- EC-196: o saldo que o BANCO informa passa a ser guardado, para poder ser
-- confrontado com o que a tela mostra.
--
-- O QUE ESTAVA ERRADO. A API do Pluggy devolve `balance` em toda conta, e nós
-- líamos a conta inteira — nome, instituição, dia de fechamento, dia de
-- vencimento — e JOGÁVAMOS O SALDO FORA. Todo número de saldo do app nasce da
-- soma dos lançamentos que conseguimos importar. Quando falta um lançamento, o
-- número fica errado e NADA no sistema sabe disso: não há segunda fonte para
-- discordar.
--
-- MEDIDO no concorrente (Pierre) em 09/09/2026, e é o defeito que dá nome ao
-- EC-196: a tela de saldos mostrava R$ 0,00 com o carimbo "Atualizado agora",
-- enquanto a tela de Conexões do mesmo app dizia que a última leitura tinha 11
-- horas. Extrato com movimento e saldo zero não é saldo: é leitura que falhou.
--
-- POR QUE DUAS COLUNAS. O valor sem a hora não serve para nada: um saldo de
-- ontem apresentado como o de hoje é exatamente o defeito que se quer evitar.
-- A hora é do PROVEDOR quando ele a informa, e da sincronização quando não.
ALTER TABLE connector_accounts
    ADD COLUMN reported_balance NUMERIC(19, 4);

ALTER TABLE connector_accounts
    ADD COLUMN reported_balance_at TIMESTAMPTZ;

-- Nulo é o estado inicial legítimo de toda conta já existente: nenhuma delas
-- foi sincronizada depois desta coluna existir, e inventar zero aqui seria
-- criar a mentira que a coluna existe para denunciar.
COMMENT ON COLUMN connector_accounts.reported_balance IS
    'Saldo informado pela instituição na última sincronização; NULL = nunca informado';
COMMENT ON COLUMN connector_accounts.reported_balance_at IS
    'Quando esse saldo foi lido; NULL junto com reported_balance';
