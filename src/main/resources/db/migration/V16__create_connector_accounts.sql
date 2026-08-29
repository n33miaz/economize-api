-- EC-113: DIMENSÃO DE CONTA no lançamento — de ONDE veio cada linha.
--
-- O problema que isto resolve. Desde o EC-106 o extrato do cartão entra pelo
-- mesmo pipeline do extrato bancário, mas a linha gravada não guardava a origem:
-- só sobravam type/amount/description/date. Com isso o usuário não conseguia
-- perguntar "o que eu gastei NO CARTÃO neste mês", a fatura não tinha como
-- abrir, e duas faturas de bancos DIFERENTES eram indistinguíveis uma da outra
-- (pendência registrada no EC-095: "fatura funde bancos — falta dimensão
-- conta"). Como no EC-106, o fato só é conhecido na IMPORTAÇÃO — depois de
-- gravada, nada na linha permite recuperá-lo sem adivinhar por texto.
--
-- Por que uma TABELA e não colunas denormalizadas na transação. O que a origem
-- carrega não é só um rótulo: o dia de fechamento e o de vencimento do cartão
-- são propriedade DA CONTA, não de cada compra. Copiá-los em toda linha os
-- faria divergir na primeira vez que o banco mudasse o vencimento. A tabela
-- também dá ao app um id estável para passar na rota da fatura e uma listagem
-- barata de "minhas origens" — que por linhas de transação exigiria um DISTINCT
-- sobre o extrato inteiro.

CREATE TABLE connector_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Vínculo com a conexão que trouxe a conta. NULLABLE e ON DELETE SET NULL de
    -- propósito: desvincular o Nubank não pode apagar a origem das compras que
    -- já foram importadas dele. O usuário desconecta a instituição, e o histórico
    -- continua sabendo dizer "isto foi no Nubank ····1234".
    pluggy_item_id UUID REFERENCES pluggy_items(id) ON DELETE SET NULL,

    -- id da conta na API do provedor, guardado como texto para não acoplar o
    -- schema ao formato de id de um terceiro (mesma decisão de pluggy_items)
    provider_account_id VARCHAR(64) NOT NULL,

    -- rótulo que o app mostra ("Ultravioleta ····1234"); montado na importação a
    -- partir do nome comercial + últimos dígitos que o provedor devolve
    name VARCHAR(120) NOT NULL,

    -- instituição, COPIADA do conector no momento do registro. Denormalizada de
    -- propósito: quando o item for desvinculado (acima), é a única coisa que
    -- ainda sabe de qual banco a conta era
    institution VARCHAR(160),

    -- BANK (conta bancária) ou CREDIT_CARD (cartão de crédito). O vocabulário é
    -- o do provedor, não "conta corrente": conta poupança também chega como BANK
    -- e chamá-la de corrente seria mentira gravada em banco
    type VARCHAR(20) NOT NULL,

    -- Metadados de fatura do provedor. INTEGER (e não SMALLINT) porque é o tipo
    -- que o Hibernate mapeia para Integer sem atrito com ddl-auto=validate.
    -- Nulável porque nem todo conector devolve as datas de fechamento e
    -- vencimento — e é a AUSÊNCIA delas que faz a API cair no ciclo do
    -- calendário e declarar isso na resposta, em vez de inventar um dia.
    statement_closing_day INTEGER,
    statement_due_day INTEGER,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A conta do provedor pertence a UM usuário. A chave é (dono, id no
    -- provedor) e não o id sozinho: ela é o alvo do upsert de cada sync, e
    -- amarrá-la ao dono impede que uma conta reaproveitada por outro item
    -- atravesse a fronteira de conta do Economize.
    CONSTRAINT uq_connector_accounts_provider UNIQUE (user_id, provider_account_id)
);

-- A listagem "minhas origens" é a única consulta que varre a tabela, sempre pelo
-- dono. Sem índice em pluggy_item_id: a tabela tem uma linha por conta bancária
-- do usuário (unidades, não milhares) e nenhuma consulta parte do item.
CREATE INDEX idx_connector_accounts_user ON connector_accounts (user_id);

-- A origem no lançamento.
--
-- NULLABLE, e sem backfill. Duas razões, nesta ordem: (1) o histórico foi
-- gravado quando o fato não era registrado — não existe nada a copiar, e deduzir
-- a conta pelo texto do descritivo é exatamente o palpite frágil que o projeto
-- recusa como sinal único; (2) o UPLOAD MANUAL (OFX/CSV/TXT/XLSX) não tem conta
-- de provedor nenhuma, hoje nem depois, então NULL não é um estado transitório
-- que uma migração futura elimina — é o valor permanente e correto para esse
-- caminho. A API o expõe como "origem não informada", nunca como uma conta
-- inventada.
--
-- ON DELETE SET NULL: apagar uma origem jamais pode apagar lançamento do
-- usuário. O pior caso é a linha voltar a ser "origem não informada", que é
-- exatamente o estado do histórico anterior a esta migração.
ALTER TABLE bank_transactions
    ADD COLUMN account_id UUID;

ALTER TABLE bank_transactions
    ADD CONSTRAINT fk_bank_transactions_account
    FOREIGN KEY (account_id) REFERENCES connector_accounts(id) ON DELETE SET NULL;

-- Índice com query real, e UMA SÓ: a montagem da fatura, que lê "os lançamentos
-- DESTA conta dentro DESTE ciclo". Entra por (account_id, date) nesta ordem —
-- account_id é seletivo e igualdade, date é faixa. user_id fica de fora porque
-- account_id já é único por usuário e o filtro de dono continua na cláusula por
-- segurança, não por desempenho.
--
-- O filtro `accountId` da listagem de transações NÃO é leitor deste índice: ele
-- é aplicado em memória sobre a janela que a listagem já lê inteira, junto dos
-- filtros de status e de categoria. É decisão de projeto, não esquecimento —
-- volume mensal de finanças pessoais é pequeno, `accountId` nulo precisa
-- devolver também os lançamentos SEM origem (upload manual), e descer só um dos
-- três filtros criaria dois caminhos de consulta para a mesma rota.
--
-- PARCIAL: `account_id IS NULL` é o valor permanente e correto de todo o
-- histórico e de todo upload manual de arquivo (ver acima). Indexar essas linhas
-- custaria o extrato inteiro de cada usuário em disco e em cada INSERT, para
-- servir consulta nenhuma — a única que existe filtra por igualdade de conta e
-- portanto nunca alcança as nulas.
CREATE INDEX idx_bank_transactions_account_date ON bank_transactions (account_id, date)
    WHERE account_id IS NOT NULL;
