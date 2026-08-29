-- EC-107: OPÇÕES DE IA — o usuário traz o próprio provedor, modelo e chave.
--
-- O problema que isto resolve. Toda chamada de IA do produto (assistente e
-- sugestão de categoria) sai hoje de UMA chave do servidor, a GEMINI_API_KEY do
-- ambiente. Isso amarra três coisas que não têm por que andar juntas: quem paga
-- (o dono do deploy), qual modelo roda (o que estiver na property) e para onde o
-- extrato bancário do usuário viaja (o provedor que o dono escolheu). Com a
-- decisão de BYOK multi-provedor (Q2 da rodada, 14/08), cada conta pode declarar
-- o próprio provedor, o próprio modelo e a própria chave — e continua valendo o
-- caminho de hoje para quem não declarar nada.
--
-- Por que UMA linha por usuário, e não um histórico de chaves. A pergunta que o
-- produto faz é sempre "qual configuração vale AGORA para esta conta". Guardar
-- várias exigiria eleger a ativa, e chave antiga guardada é superfície de
-- vazamento que não serve a nenhuma consulta: trocar de chave APAGA a anterior.
-- Daí o UNIQUE em user_id, que é a chave natural da tabela.

CREATE TABLE user_ai_settings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- UNIQUE: uma configuração ativa por conta (ver acima). ON DELETE CASCADE
    -- porque a chave do usuário não pode sobreviver à conta dele — apagar o
    -- cadastro tem que apagar o segredo junto, sem tarefa de limpeza pendente.
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,

    -- GEMINI | OPENAI | ANTHROPIC | OPENROUTER. Texto e não enum de banco: o
    -- conjunto de provedores muda mais rápido do que se troca um tipo em
    -- Postgres, e a validação de verdade está no enum Java + na allowlist de
    -- properties, que é quem responde 400 antes de qualquer INSERT.
    provider VARCHAR(20) NOT NULL,

    -- Identificador do modelo NO PROVEDOR ("gemini-2.5-flash", "gpt-4o-mini").
    -- 80 caracteres cobrem com folga os nomes com prefixo de organização que o
    -- OpenRouter usa ("anthropic/claude-sonnet-4.5").
    model VARCHAR(80) NOT NULL,

    -- A CHAVE DO USUÁRIO, CIFRADA. Nunca em claro, aqui nem em log, nem em
    -- resposta de API. O formato é um envelope autodescritivo
    -- "v1:<idDaChaveMestra>:<iv>:<textoCifrado+tag>" em AES-256-GCM (JDK, sem
    -- dependência nova). O UUID do dono entra como dado autenticado (AAD) da
    -- GCM: mover esta linha para outro user_id no banco quebra a autenticação e
    -- a decifragem falha, em vez de entregar a chave de um usuário a outro.
    --
    -- VARCHAR(1024) e não TEXT por causa do ddl-auto=validate: o tamanho
    -- declarado aqui é o mesmo do @Column da entidade, então não sobra espaço
    -- para o validador do Hibernate discordar na subida. O envelope de uma
    -- chave de 512 caracteres (o teto que a API aceita) fica perto de 760.
    api_key_cipher VARCHAR(1024) NOT NULL,

    -- Com QUAL chave-mestra esta linha foi cifrada. É o que torna a ROTAÇÃO
    -- possível sem adivinhação: o envelope acima já carrega o id (é ele que a
    -- decifragem lê), mas repetido em coluna o operador consegue perguntar
    -- "quantos segredos ainda estão na chave antiga?" com um COUNT, sem
    -- decifrar nada e sem varrer texto. A coluna é cópia do envelope, escrita
    -- no mesmo save; em divergência, o envelope é quem manda.
    master_key_id VARCHAR(32) NOT NULL,

    -- Últimos 4 caracteres da chave, EM CLARO e de propósito. Serve para o
    -- usuário reconhecer qual chave cadastrou ("...9f2a") quando tiver mais de
    -- uma no provedor — a mesma pista que Stripe, GitHub e AWS mostram. Quatro
    -- caracteres não reconstroem nada: sobram mais de 200 bits desconhecidos, e
    -- o pedaço PREVISÍVEL de uma chave é o prefixo público ("sk-"), não o fim.
    api_key_last4 VARCHAR(4),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Sem índice adicional: a única consulta é "a configuração DESTE usuário", e o
-- UNIQUE em user_id já é um índice. A tabela tem no máximo uma linha por conta.
