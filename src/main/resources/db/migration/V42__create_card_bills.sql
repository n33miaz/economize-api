-- A FATURA QUE O BANCO FECHOU, em vez da que nós deduzimos.
--
-- O QUE EXISTE HOJE. `CardInvoiceService` monta a fatura recortando os
-- lançamentos pelo dia de fechamento que o provedor informou
-- (`cycleSource = PROVIDER_CLOSING_DAY`) e somando o que temos. É uma dedução
-- honesta — o próprio contrato declara a aproximação — mas ela só conhece as
-- linhas que chegaram até nós.
--
-- POR QUE ISSO DÓI. Medido na conta do dono em 21/09/2026: o app mostrava
-- R$ 775,67 no `GOLD ····8210` enquanto a fatura FECHADA de setembro, no
-- banco, foi de R$ 2.311,49. A diferença não é erro de conta: são compras que
-- o conector não trouxe. O app não tinha como saber que faltava algo, e
-- mostrar dado incompleto sem avisar é pior do que não mostrar.
--
-- O QUE O PLUGGY JÁ ENTREGA E NÓS IGNORÁVAMOS. Sondado em 21/09 com as
-- credenciais que o dono forneceu: `GET /bills?accountId=` responde 200 com
-- **40 faturas** nas conexões dele — 13 no GOLD, 14 no gold, 12 no Free
-- Mastercard, 1 no Mercado Pago. Cada uma traz data de fechamento,
-- vencimento, total, encargos, pagamentos e o **pagamento mínimo**, que não
-- existe em lugar nenhum do app hoje.
--
-- A DECISÃO: GUARDAR AO LADO, NÃO NO LUGAR. A fatura deduzida continua sendo
-- calculada e devolvida exatamente como antes. A do banco entra como um
-- segundo conjunto de números, e é a DIFERENÇA entre os dois que vira
-- informação: quando elas divergem, o app pode dizer "o banco fechou em X e eu
-- só enxergo Y" — que é o aviso que faltava. Substituir uma pela outra jogaria
-- fora justamente esse sinal.
CREATE TABLE card_bills (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- A conta de cartão a que a fatura pertence. CASCADE: fatura sem cartão não
    -- significa nada, e desconectar o cartão tem de levar as faturas junto.
    account_id UUID NOT NULL REFERENCES connector_accounts(id) ON DELETE CASCADE,
    -- O id da fatura no provedor. É por ele que a re-sincronização ATUALIZA em
    -- vez de duplicar: uma fatura em aberto muda de total até fechar.
    external_id VARCHAR(80) NOT NULL,
    -- Quando o ciclo fechou e quando vence. O vencimento é o que o usuário
    -- lembra; o fechamento é o que recorta o ciclo.
    closing_date DATE,
    due_date DATE,
    -- Em POSITIVO, como todo valor de fatura no resto da API.
    total_amount NUMERIC(19, 4),
    minimum_payment NUMERIC(19, 4),
    finance_charges NUMERIC(19, 4),
    currency VARCHAR(3),
    allows_installments BOOLEAN NOT NULL DEFAULT FALSE,
    -- Quando NÓS lemos do provedor. Uma fatura lida há semanas pode estar
    -- velha, e a tela tem de poder dizer isso em vez de fingir atualidade.
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_card_bills_account_external UNIQUE (account_id, external_id)
);

-- A leitura é sempre "as faturas deste cartão, da mais recente para a mais
-- antiga", e o casamento com o ciclo deduzido é pelo fechamento.
CREATE INDEX idx_card_bills_account_closing ON card_bills (account_id, closing_date DESC);
CREATE INDEX idx_card_bills_user ON card_bills (user_id);
