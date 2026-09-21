-- A NOTA FISCAL amarrada à compra, pela chave de acesso do cupom.
--
-- O PEDIDO, dito de dentro do mercado em 21/09/2026: "não estou conseguindo
-- anexar a nf na última transação". Não havia nada — nem rota, nem coluna. O
-- app sabia o que a pessoa anotou no corredor e o total que ela digitou no
-- caixa, mas não tinha como dizer DE QUAL NOTA aquilo tudo era.
--
-- POR QUE A CHAVE, E NÃO UM ARQUIVO. O cupom traz um QR com a chave de acesso
-- de 44 dígitos, e ela não é um número opaco: carrega o estado, o mês, o CNPJ
-- de quem emitiu, o modelo, a série e o número da nota, mais um dígito
-- verificador que prova que foi lida certo. Guardar 44 dígitos dá tudo isso
-- por um custo de armazenamento que é praticamente zero, e sem depender de
-- ninguém — nem do portal da Fazenda, nem de upload de imagem.
--
-- O QUE ISTO NÃO RESOLVE. Os ITENS da nota não estão na chave; eles moram no
-- portal da Secretaria da Fazenda de cada estado, cada um com o seu endereço
-- e boa parte com captcha. Conferir item a item continua sendo comparar o que
-- foi anotado no corredor com o papel, e isso é honesto dizer.
--
-- ÚNICA POR USUÁRIO, e não global: duas pessoas da mesma casa podem registrar
-- a MESMA compra por engano, e o unique por usuário impede a mesma nota entrar
-- duas vezes na conta de quem quer que seja, sem impedir que a compra do
-- vizinho exista.
ALTER TABLE shopping_trips ADD COLUMN receipt_key VARCHAR(44);

-- O CNPJ sai da chave e poderia ser derivado toda vez, mas guardá-lo é o que
-- permite perguntar "quanto eu gasto NESTE mercado" sem varrer 44 dígitos de
-- cada linha em SQL.
ALTER TABLE shopping_trips ADD COLUMN receipt_issuer_cnpj VARCHAR(14);

CREATE UNIQUE INDEX uq_shopping_trips_user_receipt
    ON shopping_trips (user_id, receipt_key)
    WHERE receipt_key IS NOT NULL;

-- "as compras que fiz neste CNPJ", que é a pergunta do histórico de preço por
-- loja quando o nome digitado varia ("Carrefour", "carrefour hiper", ...)
CREATE INDEX idx_shopping_trips_issuer ON shopping_trips (user_id, receipt_issuer_cnpj);
