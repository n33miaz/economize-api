-- V38 — como a pessoa faz as compras de mercado, para o app dizer o melhor dia.
--
-- O PROBLEMA. O salário não cai "dia 5": cai no 3º, 4º ou 5º dia útil, e o
-- vale cai cinco dias úteis antes dele. Quem compra no sábado errado gasta o
-- dinheiro que ainda não entrou — e o app só conseguia responder "quando cai"
-- em dia do calendário. A previsão do padrão de entradas é DERIVADA do extrato
-- a cada leitura (nada é gravado em income_sources nem em recurring_series: o
-- app propõe, não move). O que precisa ser guardado é só o que o extrato não
-- conta: se a pessoa compra uma vez por mês ou toda semana, se prefere o fim
-- de semana e se paga no dinheiro da conta ou no cartão.
--
-- POR QUE TABELA NOVA, E NÃO COLUNA EM work_profiles. A jornada é 1:1 com
-- days_per_week/hours_per_day NOT NULL e "ausência é informação": guardar a
-- cadência de compra lá obrigaria a inventar uma jornada para quem só quer
-- dizer "compro mensal", e o PUT /work-profile substitui o registro inteiro —
-- apagaria a preferência a cada edição de horas. Também não é income_sources:
-- cadência é de COMPRA, não de renda.
CREATE TABLE purchase_preferences (
    -- 1:1 com o usuário, como work_profiles: a PK é a própria FK
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,

    -- MONTHLY / WEEKLY. Texto e não enum do banco pela mesma razão de
    -- sweep_runs.kind: cadência nova não pode exigir migration.
    cadence VARCHAR(8) NOT NULL,

    -- A maioria das compras grandes de mercado acontece no sábado; o padrão
    -- verdadeiro reflete isso, e quem compra em dia de semana desliga.
    weekend_preferred BOOLEAN NOT NULL DEFAULT TRUE,

    -- CASH (dinheiro da conta) / CARD (cartão de crédito). NULL = deixar o
    -- app decidir — nulo aqui é "não sei", nunca um padrão escondido.
    payment_mode VARCHAR(8),

    -- Qual cartão, quando payment_mode = CARD: é dele que sai o dia de
    -- fechamento que define "a compra entra na fatura que vem". ON DELETE
    -- SET NULL porque desvincular o banco não pode apagar a preferência de
    -- cadência que a pessoa declarou.
    card_account_id UUID REFERENCES connector_accounts(id) ON DELETE SET NULL,

    -- Qual fonte paga o mercado (SALARY / MEAL_VOUCHER / FOOD_VOUCHER). NULL =
    -- o app escolhe pela ordem VA > VR > salário.
    funding_kind VARCHAR(16),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- "Pago no cartão" sem dizer qual não tem como virar recomendação: o dia
    -- de fechamento é do cartão, não da pessoa.
    CONSTRAINT ck_purchase_preferences_card CHECK (payment_mode <> 'CARD' OR card_account_id IS NOT NULL)
);

COMMENT ON TABLE purchase_preferences IS
    'Como a pessoa faz as compras de mercado (cadencia, fim de semana, forma de pagamento). Uma linha por usuario.';
COMMENT ON COLUMN purchase_preferences.payment_mode IS
    'CASH ou CARD. Nulo = o app decide.';
COMMENT ON COLUMN purchase_preferences.funding_kind IS
    'Fonte de renda que paga o mercado (SALARY, MEAL_VOUCHER, FOOD_VOUCHER). Nulo = o app escolhe.';
