-- EC-204: teto de gasto por categoria, com aviso ao cruzar.
--
-- POR QUE UMA TABELA E NÃO UMA COLUNA EM categories. A categoria é
-- compartilhada: as do sistema (user_id NULL) servem a todo mundo, e um teto
-- gravado nelas seria o teto de uma pessoa valendo para todas. O teto é do
-- USUÁRIO sobre uma categoria, que é exatamente o que esta tabela diz.
--
-- POR QUE monthly_limit E NÃO "limite do ciclo". O recorte do ciclo é escolha
-- de tela (o dono ancora o mês no dia 12, por exemplo) e pode mudar amanhã. O
-- teto é uma intenção mensal — "não quero passar de R$ 800 em mercado" — e
-- guardá-la presa a um recorte faria a intenção mudar junto com a tela.
-- Quem faz a regra de três com o tamanho da janela é o serviço.
CREATE TABLE category_budgets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- ON DELETE CASCADE: categoria apagada leva o teto junto. Um teto órfão
    -- não tem sobre o que avisar.
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,

    -- Sempre positivo: é um teto de GASTO, e o extrato guarda despesa em
    -- negativo. Misturar os dois sinais aqui garantiria uma comparação
    -- invertida em algum lugar meses depois.
    monthly_limit NUMERIC(19, 4) NOT NULL CHECK (monthly_limit > 0),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Um teto por categoria por pessoa. Dois tetos para a mesma categoria é
    -- uma pergunta sem resposta, e o upsert do serviço depende desta chave.
    CONSTRAINT uq_category_budgets_user_category UNIQUE (user_id, category_id)
);

CREATE INDEX idx_category_budgets_user ON category_budgets (user_id);
