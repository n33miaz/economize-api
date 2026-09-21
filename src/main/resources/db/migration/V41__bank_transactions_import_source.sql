-- A PROCEDÊNCIA de um lançamento passa a ser GRAVADA, não deduzida.
--
-- O DEFEITO. `BankTransaction.importSource()` decidia assim: tem `account_id`
-- → veio da CONEXÃO; senão tem `upload_id` → veio de ARQUIVO; senão
-- desconhecido. A ordem fazia sentido quando foi escrita, porque toda linha do
-- conector carrega os dois. Só que o upload de arquivo TAMBÉM carimba
-- `account_id`: quando a pessoa diz "este OFX é da conta do Inter"
-- (`processBytes`), e de novo quando ela atribui a conta depois
-- (`assignUploadAccount`). A partir daí, cada linha daquele arquivo passava a
-- se apresentar como se tivesse vindo do conector.
--
-- Não é detalhe de exibição. O dono importa arquivo E usa conector no mesmo
-- banco, e foi exatamente a mistura das duas fontes que ele descreveu como
-- "os números parecem meio embaralhados". A tela que existe para responder
-- "de onde veio esta linha" respondia errado justamente nas contas em que a
-- pergunta importa.
--
-- POR QUE GRAVAR, EM VEZ DE CONSERTAR A DEDUÇÃO. Procedência é um fato do
-- momento da importação, e o momento da importação é a única hora em que ele é
-- conhecido com certeza. Deduzi-lo de `account_id` amarra um fato imutável a um
-- campo que MUDA depois — e essa é a causa raiz, não a ordem dos `if`. Quem
-- sabe a verdade é o formato do upload: `PLUGGY` é conector, qualquer outro é
-- arquivo.
--
-- NULO CONTINUA VÁLIDO. Linha sem upload nenhum é histórico anterior ao
-- registro de origem; para ela a dedução antiga permanece como último recurso,
-- e nada muda. O backfill abaixo cobre tudo que tem upload, que é todo o
-- histórico desde que uploads existem.
ALTER TABLE bank_transactions ADD COLUMN import_source VARCHAR(16);

UPDATE bank_transactions t
SET import_source = CASE WHEN u.format = 'PLUGGY' THEN 'CONNECTION' ELSE 'FILE' END
FROM statement_uploads u
WHERE t.upload_id = u.id
  AND t.import_source IS NULL;

-- A leitura é sempre "as linhas deste usuário", e a procedência entra como
-- filtro de tela ("só o que o conector não vê"), nunca como busca isolada —
-- por isso o índice acompanha o usuário em vez de ficar sozinho na coluna.
CREATE INDEX IF NOT EXISTS idx_bank_transactions_user_import_source
    ON bank_transactions (user_id, import_source);
