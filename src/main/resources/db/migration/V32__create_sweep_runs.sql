-- EC-202: as varreduras deixam de ser invisíveis e ganham recado com desfazer.
--
-- O QUE ESTAVA ERRADO. Seis varreduras rodam sozinhas depois de cada
-- importação — movimentação própria, aplicação/resgate, casa, duplicatas,
-- estornos e recorrência. Elas MUDAM os números do usuário e não deixam
-- rastro nenhum: nada na tela diz que rodaram, o que acharam, nem como
-- desfazer. O dono abre o app e a soma mudou.
--
-- Trabalho automático sem prestação de contas é o que faz alguém desconfiar
-- do app inteiro — e a marca é reversível linha a linha, mas ninguém sabia
-- QUAIS linhas olhar.
--
-- POR QUE DUAS TABELAS. O resumo (`sweep_runs`) é o que a aba de recados lê:
-- é curto, é lido sempre e cabe numa lista. Os ids afetados
-- (`sweep_run_items`) só são lidos no DESFAZER, e são muitos — 350 linhas
-- numa única varredura de investimento no extrato do dono. Guardar os dois
-- juntos faria a lista de recados carregar milhares de UUIDs para desenhar
-- seis frases.
CREATE TABLE sweep_runs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Qual vigia. Texto e não enum do banco: um vigia novo não pode exigir
    -- migration, e o nome de exibição mora no código, junto da regra dele.
    kind VARCHAR(32) NOT NULL,

    -- Quantas linhas (ou pares) o vigia mexeu nesta passada. Zero é registro
    -- legítimo e útil: "rodei e não achei nada" é resposta, e sem ela o
    -- silêncio é ambíguo.
    affected INT NOT NULL DEFAULT 0,

    -- Volume em dinheiro do que saiu das somas, quando o vigia sabe dizer.
    -- NULL para os que não têm valor (detecção de recorrência).
    volume NUMERIC(19, 4),

    ran_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Desfeito pelo usuário: a passada continua no histórico com a marca, e
    -- não é apagada. Apagar esconderia que o vigia errou, que é exatamente o
    -- que o histórico existe para mostrar.
    undone_at TIMESTAMPTZ
);

CREATE INDEX idx_sweep_runs_user_time ON sweep_runs (user_id, ran_at DESC);

-- As linhas que a passada tocou, para o desfazer saber o que soltar. Só as
-- que ELA mudou: uma linha que já estava marcada antes não entra, senão o
-- desfazer desfaria decisão de outra pessoa (ou do próprio usuário).
CREATE TABLE sweep_run_items (
    run_id UUID NOT NULL REFERENCES sweep_runs(id) ON DELETE CASCADE,
    transaction_id UUID NOT NULL REFERENCES bank_transactions(id) ON DELETE CASCADE,
    PRIMARY KEY (run_id, transaction_id)
);

CREATE INDEX idx_sweep_run_items_run ON sweep_run_items (run_id);
