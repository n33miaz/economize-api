-- Buraco encontrado ao reimportar o extrato real: "seguro protecao perda de
-- renda" não tinha onde cair. Plano de saúde já vive em Saúde, mas seguro de
-- vida/auto/residência é outra linha de orçamento — Pluggy e Plaid também
-- tratam Insurance como categoria própria.
INSERT INTO categories (id, user_id, name, slug, group_name, flow, icon, system_key) VALUES
    ('c0000000-0000-4000-8000-000000000015', NULL, 'Seguros', 'seguros', 'Essenciais', 'EXPENSE', 'shield', 'INSURANCE');

INSERT INTO categories (id, user_id, name, slug, flow, icon, system_key, parent_id) VALUES
    ('c1000000-0000-4000-8000-000000000058', NULL, 'Seguro de vida',      'seguro-de-vida',      'EXPENSE', 'shield', 'INSURANCE_LIFE',    'c0000000-0000-4000-8000-000000000015'),
    ('c1000000-0000-4000-8000-000000000059', NULL, 'Seguro do carro',     'seguro-do-carro',     'EXPENSE', 'shield', 'INSURANCE_VEHICLE', 'c0000000-0000-4000-8000-000000000015'),
    ('c1000000-0000-4000-8000-000000000060', NULL, 'Seguro residencial',  'seguro-residencial',  'EXPENSE', 'shield', 'INSURANCE_HOME',    'c0000000-0000-4000-8000-000000000015');
