-- EC-205: o progresso de uma meta passa a ter rastro.
--
-- O QUE ESTAVA ERRADO. `wishes.saved_amount` é um número que a pessoa digita.
-- Ele responde "quanto eu já juntei" e não responde NADA sobre como chegou ali:
-- não dá para saber quando subiu, de quanto foi cada aporte, nem desfazer um
-- erro de digitação sem recalcular de cabeça. É exatamente o defeito que o
-- EC-202 fechou nas varreduras — número que muda sem deixar recado —, e aqui
-- ele é pior, porque quem muda é a própria pessoa e ela esquece.
--
-- POR QUE UMA TABELA E NÃO UM HISTÓRICO EM JSON. Aporte é linha: tem data,
-- valor, origem e desfazer. Guardar isso como texto num campo impediria somar
-- no banco e transformaria "quanto entrou em março" numa leitura de string.
--
-- POR QUE `saved_amount` CONTINUA EXISTINDO. Ele vira o SALDO — a soma dos
-- aportes — e segue sendo a coluna que a projeção lê. Recalcular a soma a cada
-- leitura custaria uma agregação por desejo em toda abertura da tela, e a
-- projeção já é a parte cara. A tabela é o extrato; a coluna é o saldo.
--
-- POR QUE A ORIGEM IMPORTA. "Guardei a sobra do ciclo" e "digitei 500" são
-- fatos diferentes: o primeiro veio de um número que o app mediu, o segundo de
-- um que a pessoa afirmou. Misturar os dois faria o app apresentar como medido
-- algo que foi informado — a mesma distinção que o EC-206 pede na previsão.
CREATE TABLE wish_contributions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- ON DELETE CASCADE: desejo apagado leva os aportes. Aporte órfão não
    -- responde a pergunta nenhuma.
    wish_id UUID NOT NULL REFERENCES wishes(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Positivo guarda, negativo devolve. Zero não é aporte, é ruído: quem
    -- errou o valor corrige com um aporte negativo, que é o desfazer honesto
    -- (some do saldo e FICA no extrato).
    amount NUMERIC(19, 4) NOT NULL CHECK (amount <> 0),

    -- MEASURED = veio de um número que o app mediu (a sobra do ciclo).
    -- DECLARED = a pessoa digitou. Texto e não enum do banco, pela mesma razão
    -- de `sweep_runs.kind`: origem nova não pode exigir migration.
    origin VARCHAR(16) NOT NULL DEFAULT 'DECLARED',

    -- O ciclo de onde a sobra saiu, quando a origem é medida. NULL no aporte
    -- digitado — e nulo aqui é informação: quer dizer "não veio de ciclo
    -- nenhum", não "esqueci de gravar".
    cycle_month VARCHAR(7),

    note VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_wish_contributions_wish ON wish_contributions (wish_id, created_at DESC);

-- O mesmo ciclo não entra duas vezes na mesma meta. Sem isto, abrir a tela e
-- tocar duas vezes em "guardar a sobra de setembro" dobraria o progresso com
-- dinheiro que existiu uma vez só. Parcial porque o aporte digitado não tem
-- ciclo e pode repetir à vontade.
CREATE UNIQUE INDEX uq_wish_contributions_cycle
    ON wish_contributions (wish_id, cycle_month)
    WHERE cycle_month IS NOT NULL;
