-- EC-209: "falar com uma pessoa" com prazo e histórico que não some.
--
-- O QUE O CONCORRENTE FAZ, E POR QUE NÃO SERVE. Suporte que vive dentro de um
-- chat efêmero some quando o app fecha: a pessoa descreve o problema, fecha o
-- app, volta no dia seguinte e não encontra nem o que escreveu nem se alguém
-- leu. O efeito prático é que ela desiste — que é, do ponto de vista de quem
-- mede chamados, indistinguível de "problema resolvido".
--
-- POR QUE NO BANCO, E NÃO NO APARELHO. Um chamado guardado só no aparelho
-- morre na troca de celular, no "apagar dados locais" e no login em outro
-- lugar — exatamente nos momentos em que a pessoa mais precisa provar que
-- pediu ajuda. E ninguém do outro lado conseguiria ler.
--
-- O PRAZO É COLUNA, NÃO TEXTO DE TELA. "Respondemos em até 2 dias úteis" numa
-- string de interface é promessa que ninguém consegue medir depois. Gravado
-- por chamado, ele vira o número que o app mostra E o que diz se a promessa
-- foi cumprida.
CREATE TABLE support_tickets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- O assunto, escolhido numa lista curta. Texto e não enum do banco pelo
    -- mesmo motivo de `sweep_runs.kind`: assunto novo não pode exigir
    -- migration.
    subject VARCHAR(32) NOT NULL,

    message TEXT NOT NULL,

    -- OPEN -> ANSWERED -> CLOSED. Guardado como texto pela mesma razão.
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',

    -- Até quando prometemos responder. Gravado na abertura: se a política de
    -- prazo mudar amanhã, o chamado de hoje mantém a promessa de hoje.
    respond_by TIMESTAMPTZ NOT NULL,

    -- A resposta e quando ela veio. NULL enquanto ninguém respondeu, e nulo
    -- aqui é informação: é o que faz a tela dizer "aguardando" em vez de
    -- mostrar uma conversa vazia.
    answer TEXT,
    answered_at TIMESTAMPTZ,

    -- O que a pessoa estava vendo quando pediu ajuda. Sem isto, metade dos
    -- chamados começa com uma ida e volta só para descobrir a tela.
    app_version VARCHAR(20),
    screen VARCHAR(40),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_support_tickets_user ON support_tickets (user_id, created_at DESC);

-- Os abertos primeiro, para quem for responder. Parcial porque chamado
-- fechado é histórico e não entra na fila.
CREATE INDEX idx_support_tickets_fila
    ON support_tickets (respond_by)
    WHERE status <> 'CLOSED';
