-- O limite de crédito do cartão, informado por quem sabe: o dono da conta.
--
-- O QUE FALTAVA. Em 15/09/2026 o dono olhou a Home e disse: "não tem nada nas
-- minhas contas, atualmente eu só tenho saldo disponível em crédito nos
-- cartões — mas sempre devemos deixar bem claro isso". O app não tinha como
-- mostrar isso, e não por descuido: o limite NÃO está em lugar nenhum do que
-- importamos. O extrato de cartão (OFX/CSV) traz as compras e, no bloco de
-- saldo, o valor DEVIDO. Limite é outro número, e nenhum arquivo de fatura o
-- declara. O Pluggy devolve limite em algumas instituições e em outras não.
--
-- Por isso a coluna é DECLARADA. A pergunta ao usuário não é preguiça de
-- integração: é a única fonte que existe para todo mundo.
ALTER TABLE connector_accounts
    ADD COLUMN credit_limit NUMERIC(19, 4);

-- UM LIMITE, VÁRIOS CARTÕES. Foi o próprio dono quem levantou: "normalmente um
-- crédito vale para vários cartões". É o caso comum do cartão físico + virtual,
-- e do adicional de familiar — todos consomem a MESMA bolsa. Somar o limite de
-- cada um deles mostraria o dobro ou o triplo do crédito que a pessoa tem.
--
-- Modelado como ponteiro para a conta que é dona do limite, e não como um
-- "grupo" com tabela própria: grupo exigiria tela para gerenciar grupos, e o
-- que o usuário quer dizer é sempre "este cartão usa o limite daquele ali".
-- Quem aponta para outra conta não entra na soma; quem tem o próprio valor,
-- entra uma vez.
ALTER TABLE connector_accounts
    ADD COLUMN credit_limit_shared_with UUID;

ALTER TABLE connector_accounts
    ADD CONSTRAINT fk_connector_accounts_limit_shared_with
        FOREIGN KEY (credit_limit_shared_with)
        REFERENCES connector_accounts (id)
        ON DELETE SET NULL;

-- Um cartão não pode apontar para si mesmo: seria um limite que se soma e não
-- se soma ao mesmo tempo, e a leitura de "quanto tenho de crédito" passaria a
-- depender da ordem em que as contas são lidas.
ALTER TABLE connector_accounts
    ADD CONSTRAINT ck_connector_accounts_limit_not_self
        CHECK (credit_limit_shared_with IS NULL OR credit_limit_shared_with <> id);

COMMENT ON COLUMN connector_accounts.credit_limit IS
    'Limite total do cartão, informado pelo usuário; NULL = nunca informado';
COMMENT ON COLUMN connector_accounts.credit_limit_shared_with IS
    'Conta dona do limite quando ele é compartilhado; NULL = limite próprio';
