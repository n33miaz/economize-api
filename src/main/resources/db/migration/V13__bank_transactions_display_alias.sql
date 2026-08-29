-- EC-094: apelido de transação. O extrato entrega o descritivo do banco
-- ("PAG*ACME 4321 SAO PAULO BRA"), que o usuário muitas vezes não reconhece —
-- o apelido é o rótulo DELE para a mesma transação e convive com o original:
-- description continua guardando exatamente o que o banco mandou.
--
-- A coluna é nullable de propósito: apelido é exceção, não regra, e transação
-- sem apelido se apresenta com o descritivo do banco (a resolução fica na
-- borda, nos DTOs de resposta). Nada de backfill — não existe apelido a
-- adivinhar para o histórico.
--
-- Nenhum índice: o campo é de APRESENTAÇÃO. Quem categoriza, agrupa a revisão,
-- casa recorrência e deduplica importação continua lendo description e
-- normalized_description — o apelido nunca entra em WHERE nem em GROUP BY.
ALTER TABLE bank_transactions
    -- 80 caracteres porque isto é rótulo de reconhecimento ("Academia",
    -- "Aluguel do apê"), não uma segunda descrição: description tem 255 para
    -- caber o descritivo concatenado dos parsers, e um apelido longo demais
    -- estouraria a linha da lista no app. O mesmo limite é validado na API.
    ADD COLUMN display_alias VARCHAR(80);
