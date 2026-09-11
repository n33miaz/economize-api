-- EC-194: a compra estornada para de contar, sem sumir do extrato.
--
-- POR QUE UM PAR, E NÃO UMA MARCA SÓ. Um estorno tem duas linhas reais no
-- extrato: a compra que saiu e o crédito que voltou. As duas existem, o saldo
-- fecha com as duas, e apagar qualquer uma falsificaria o extrato. O que está
-- errado é SOMÁ-LAS: quem gastou R$ 4,00 e recebeu R$ 4,00 de volta não gastou
-- nada, e hoje o app conta R$ 4,00 de despesa e R$ 4,00 de receita.
--
-- POR QUE NÃO REUSAR internal_transfer. Lá o dinheiro trocou de bolso e os dois
-- bolsos são do dono. Aqui o dinheiro FOI e VOLTOU do mesmo lugar: não houve
-- movimento nenhum, e a pergunta "quanto entrou" tem resposta diferente nos
-- dois casos.
--
-- POR QUE NÃO REUSAR ignored. Ignorada quer dizer "esta linha não deveria
-- existir" (duplicata). As duas linhas de um estorno existem e estão certas.
--
-- MEDIDO nos extratos reais do dono em 09/09/2026: o extrato do Inter de
-- 12/08/2024 a 11/08/2026 tem NOVE lançamentos com histórico "Estorno". Oito
-- têm contraparte de mesmo valor no mesmo dia; o nono (22/04/2026, R$ 539,70)
-- é o banco zerando um saldo negativo, não o estorno de uma compra — e é por
-- isso que a varredura só marca quando acha o par exato.
ALTER TABLE bank_transactions
    ADD COLUMN refunded BOOLEAN NOT NULL DEFAULT FALSE;

-- O vínculo, para a tela poder dizer "estorno de <compra>" e para o desfazer
-- saber quem soltar junto. ON DELETE SET NULL: se a compra sumir por algum
-- caminho futuro, o crédito continua no extrato — sem vínculo, mas presente.
ALTER TABLE bank_transactions
    ADD COLUMN refund_of_id UUID REFERENCES bank_transactions (id) ON DELETE SET NULL;

-- Parcial: a esmagadora maioria das linhas não é estorno, e o índice existe
-- para a varredura achar os pares já marcados e para o desfazer.
CREATE INDEX idx_bank_transactions_refunded
    ON bank_transactions (user_id)
    WHERE refunded = TRUE;

CREATE INDEX idx_bank_transactions_refund_of
    ON bank_transactions (refund_of_id)
    WHERE refund_of_id IS NOT NULL;
