-- Extratos reais mostraram que ~20% das transações são operações de
-- investimento (Aplicação/Resgate/CDB/Tesouro no Inter) — merecem seed próprio
-- em vez de caírem em "Outros". Flow BOTH: aplicação sai, resgate volta.
INSERT INTO categories (id, user_id, name, slug, group_name, flow, icon, system_key) VALUES
    ('c0000000-0000-4000-8000-000000000012', NULL, 'Investimentos', 'investimentos', 'Movimentações', 'BOTH', 'piggy-bank', 'INVESTMENT');
