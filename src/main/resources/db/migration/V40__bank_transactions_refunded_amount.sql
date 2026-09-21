-- O estorno PARCIAL abate a compra em vez de virar transferência solta.
--
-- O CASO. Na fatura do dono, 04/08/2026: compra `Mercadolivre*Homenow` de
-- R$ 604,91; 08/08: `Crédito de "MERCADOLIVRE*HOMENOW"` de R$ 18,50. O par
-- não é um estorno inteiro, então a varredura de estornos (V29) reportava e
-- deliberadamente NÃO marcava — marcar só o crédito tiraria os 18,50 da
-- receita e deixaria a compra cheia na despesa, o que errava mais do que
-- deixar os dois contando. Em 17/09 o dono escolheu o tratamento certo:
-- "abater a compra". Agosto passa a mostrar R$ 586,41 na categoria da
-- compra, e o crédito sai do extrato como linha independente (vira vínculo).
--
-- POR QUE UMA COLUNA. O valor devolvido fica na PRÓPRIA compra, e toda soma
-- do app lê `amount + refunded_amount`. A alternativa — somar os créditos
-- ligados por `refund_of_id` na hora de cada consulta — falha quando o
-- crédito cai fora da janela consultada (o estorno costuma vir na fatura
-- seguinte, até 45 dias depois): a compra de agosto abateria só se o mês de
-- setembro estivesse na mesma consulta. A coluna torna o abate uma
-- propriedade da linha, e o desfazer da passada (EC-202) zera a coluna junto
-- com a marca do crédito.
ALTER TABLE bank_transactions
    ADD COLUMN refunded_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN bank_transactions.refunded_amount IS
    'Quanto desta compra foi devolvido por estornos PARCIAIS vinculados (positivo); as somas usam amount + refunded_amount';
