-- EC-208: cancelar o Plus em um toque, com a data em que a cobrança para.
--
-- O QUE FALTAVA. `users.plan` diz o que a conta é hoje e `users.plan_until` diz
-- até quando; nenhum dos dois diz se a pessoa PEDIU para sair. Sem essa
-- terceira informação o app não consegue responder a única pergunta que importa
-- depois de cancelar: "então tá cancelado mesmo?". Ele mostraria "Plus ativo"
-- até a data virar, que é verdade e é exatamente o que faz alguém cancelar
-- duas vezes, mandar e-mail e desconfiar da cobrança seguinte.
--
-- POR QUE UMA COLUNA E NÃO UM STATUS NOVO. Cancelado não é um estado ao lado de
-- FREE e PLUS: é PLUS com data de fim e sem renovação. Criar um terceiro valor
-- no enum obrigaria toda checagem de `isPlus()` a lembrar dele, e a primeira
-- que esquecesse tiraria o acesso de alguém que ainda pagou pelo mês.
--
-- O ACESSO NÃO É CORTADO NA HORA. Quem pagou até o dia 20 usa até o dia 20 —
-- cancelar encerra a RENOVAÇÃO, não o que já foi pago. Cortar na hora seria
-- ficar com o dinheiro e tirar o serviço.
ALTER TABLE users
    ADD COLUMN plan_cancelled_at TIMESTAMPTZ;

COMMENT ON COLUMN users.plan_cancelled_at IS
    'Quando o usuário pediu para sair do plano pago; NULL = não pediu. O acesso segue até plan_until';
