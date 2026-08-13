-- Categorias ganham um segundo nível. A referência é a prática consolidada do
-- mercado: Plaid PFCv2 (16 primárias / 104 detalhadas) e a árvore do Pluggy —
-- ambas com DOIS níveis, onde o pai é a unidade de leitura/orçamento e o filho
-- é a unidade de intenção (onde keywords e enriquecimento acertam). Um terceiro
-- nível foi descartado de propósito: ninguém mantém.
--
-- Sem "Outros X" por pai (como o Plaid tem): aqui o próprio pai é o catch-all,
-- porque nossos pais são categorias reais, não buckets abstratos.
ALTER TABLE categories
    ADD COLUMN parent_id UUID REFERENCES categories(id) ON DELETE CASCADE;

CREATE INDEX idx_categories_parent ON categories (parent_id);

COMMENT ON COLUMN categories.parent_id IS
    'Categoria pai. NULL = raiz. A profundidade máxima de 2 é garantida no CategoryService: quem tem pai não pode ser pai.';

-- Dois pais novos que os extratos reais e as duas taxonomias de referência pedem:
-- cuidado pessoal (PERSONAL_CARE no Plaid) e o par imposto/tarifa, que no extrato
-- do Inter aparece como imposto, IOF e tarifa e hoje caía em "Contas e serviços".
INSERT INTO categories (id, user_id, name, slug, group_name, flow, icon, system_key) VALUES
    ('c0000000-0000-4000-8000-000000000013', NULL, 'Cuidado pessoal',     'cuidado-pessoal',     'Estilo de vida', 'EXPENSE', 'dumbbell',   'PERSONAL_CARE'),
    ('c0000000-0000-4000-8000-000000000014', NULL, 'Impostos e tarifas',  'impostos-e-tarifas',  'Movimentações',  'EXPENSE', 'hand-coins', 'FEES_TAXES');

-- Subcategorias: group_name fica NULL porque quem agrupa agora é o pai.
-- A cor também fica NULL — o cliente resolve pela cor do pai, mantendo a regra
-- "a cor segue a entidade" (03_DESIGN_SYSTEM §2.1).
INSERT INTO categories (id, user_id, name, slug, flow, icon, system_key, parent_id) VALUES
    -- Alimentação
    ('c1000000-0000-4000-8000-000000000001', NULL, 'Mercado',                    'mercado',                    'EXPENSE', 'shopping-bag',     'FOOD_GROCERIES',       'c0000000-0000-4000-8000-000000000001'),
    ('c1000000-0000-4000-8000-000000000002', NULL, 'Restaurantes e bares',       'restaurantes-e-bares',       'EXPENSE', 'utensils',         'FOOD_RESTAURANT',      'c0000000-0000-4000-8000-000000000001'),
    ('c1000000-0000-4000-8000-000000000003', NULL, 'Delivery',                   'delivery',                   'EXPENSE', 'smartphone',       'FOOD_DELIVERY',        'c0000000-0000-4000-8000-000000000001'),
    ('c1000000-0000-4000-8000-000000000004', NULL, 'Padaria e café',             'padaria-e-cafe',             'EXPENSE', 'coffee',           'FOOD_COFFEE',          'c0000000-0000-4000-8000-000000000001'),
    -- Transporte
    ('c1000000-0000-4000-8000-000000000005', NULL, 'Combustível',                'combustivel',                'EXPENSE', 'fuel',             'TRANSPORT_FUEL',       'c0000000-0000-4000-8000-000000000002'),
    ('c1000000-0000-4000-8000-000000000006', NULL, 'Transporte público',         'transporte-publico',         'EXPENSE', 'arrow-left-right', 'TRANSPORT_PUBLIC',     'c0000000-0000-4000-8000-000000000002'),
    ('c1000000-0000-4000-8000-000000000007', NULL, 'Aplicativos de transporte',  'aplicativos-de-transporte',  'EXPENSE', 'car',              'TRANSPORT_RIDE',       'c0000000-0000-4000-8000-000000000002'),
    ('c1000000-0000-4000-8000-000000000008', NULL, 'Estacionamento e pedágio',   'estacionamento-e-pedagio',   'EXPENSE', 'car',              'TRANSPORT_PARKING',    'c0000000-0000-4000-8000-000000000002'),
    ('c1000000-0000-4000-8000-000000000009', NULL, 'Manutenção e documentação',  'manutencao-e-documentacao',  'EXPENSE', 'wrench',           'TRANSPORT_VEHICLE',    'c0000000-0000-4000-8000-000000000002'),
    -- Moradia
    ('c1000000-0000-4000-8000-000000000010', NULL, 'Aluguel',                    'aluguel',                    'EXPENSE', 'house',            'HOUSING_RENT',         'c0000000-0000-4000-8000-000000000006'),
    ('c1000000-0000-4000-8000-000000000011', NULL, 'Condomínio',                 'condominio',                 'EXPENSE', 'house',            'HOUSING_CONDO',        'c0000000-0000-4000-8000-000000000006'),
    ('c1000000-0000-4000-8000-000000000012', NULL, 'Casa e móveis',              'casa-e-moveis',              'EXPENSE', 'wrench',           'HOUSING_GOODS',        'c0000000-0000-4000-8000-000000000006'),
    ('c1000000-0000-4000-8000-000000000013', NULL, 'IPTU e taxas do imóvel',     'iptu-e-taxas-do-imovel',     'EXPENSE', 'house',            'HOUSING_PROPERTY_TAX', 'c0000000-0000-4000-8000-000000000006'),
    -- Contas e serviços
    ('c1000000-0000-4000-8000-000000000014', NULL, 'Energia',                    'energia',                    'EXPENSE', 'plug-zap',         'UTILITIES_ELECTRICITY','c0000000-0000-4000-8000-000000000008'),
    ('c1000000-0000-4000-8000-000000000015', NULL, 'Água e esgoto',              'agua-e-esgoto',              'EXPENSE', 'plug-zap',         'UTILITIES_WATER',      'c0000000-0000-4000-8000-000000000008'),
    ('c1000000-0000-4000-8000-000000000016', NULL, 'Gás',                        'gas',                        'EXPENSE', 'plug-zap',         'UTILITIES_GAS',        'c0000000-0000-4000-8000-000000000008'),
    ('c1000000-0000-4000-8000-000000000017', NULL, 'Internet e TV',              'internet-e-tv',              'EXPENSE', 'smartphone',       'UTILITIES_INTERNET',   'c0000000-0000-4000-8000-000000000008'),
    ('c1000000-0000-4000-8000-000000000018', NULL, 'Celular',                    'celular',                    'EXPENSE', 'smartphone',       'UTILITIES_PHONE',      'c0000000-0000-4000-8000-000000000008'),
    -- Saúde
    ('c1000000-0000-4000-8000-000000000019', NULL, 'Farmácia',                   'farmacia',                   'EXPENSE', 'heart-pulse',      'HEALTH_PHARMACY',      'c0000000-0000-4000-8000-000000000004'),
    ('c1000000-0000-4000-8000-000000000020', NULL, 'Consultas e exames',         'consultas-e-exames',         'EXPENSE', 'heart-pulse',      'HEALTH_CARE',          'c0000000-0000-4000-8000-000000000004'),
    ('c1000000-0000-4000-8000-000000000021', NULL, 'Plano de saúde',             'plano-de-saude',             'EXPENSE', 'heart-pulse',      'HEALTH_INSURANCE',     'c0000000-0000-4000-8000-000000000004'),
    ('c1000000-0000-4000-8000-000000000022', NULL, 'Dentista',                   'dentista',                   'EXPENSE', 'heart-pulse',      'HEALTH_DENTAL',        'c0000000-0000-4000-8000-000000000004'),
    -- Cuidado pessoal
    ('c1000000-0000-4000-8000-000000000023', NULL, 'Academia',                   'academia',                   'EXPENSE', 'dumbbell',         'PERSONAL_GYM',         'c0000000-0000-4000-8000-000000000013'),
    ('c1000000-0000-4000-8000-000000000024', NULL, 'Beleza e cabelo',            'beleza-e-cabelo',            'EXPENSE', 'tag',              'PERSONAL_BEAUTY',      'c0000000-0000-4000-8000-000000000013'),
    ('c1000000-0000-4000-8000-000000000025', NULL, 'Lavanderia',                 'lavanderia',                 'EXPENSE', 'shirt',            'PERSONAL_LAUNDRY',     'c0000000-0000-4000-8000-000000000013'),
    -- Educação
    ('c1000000-0000-4000-8000-000000000026', NULL, 'Faculdade e escola',         'faculdade-e-escola',         'EXPENSE', 'graduation-cap',   'EDUCATION_SCHOOL',     'c0000000-0000-4000-8000-000000000005'),
    ('c1000000-0000-4000-8000-000000000027', NULL, 'Cursos',                     'cursos',                     'EXPENSE', 'graduation-cap',   'EDUCATION_COURSES',    'c0000000-0000-4000-8000-000000000005'),
    ('c1000000-0000-4000-8000-000000000028', NULL, 'Livros e materiais',         'livros-e-materiais',         'EXPENSE', 'tag',              'EDUCATION_BOOKS',      'c0000000-0000-4000-8000-000000000005'),
    -- Lazer
    ('c1000000-0000-4000-8000-000000000029', NULL, 'Streaming e assinaturas',    'streaming-e-assinaturas',    'EXPENSE', 'music',            'LEISURE_STREAMING',    'c0000000-0000-4000-8000-000000000003'),
    ('c1000000-0000-4000-8000-000000000030', NULL, 'Jogos',                      'jogos',                      'EXPENSE', 'gamepad-2',        'LEISURE_GAMES',        'c0000000-0000-4000-8000-000000000003'),
    ('c1000000-0000-4000-8000-000000000031', NULL, 'Cinema, shows e eventos',    'cinema-shows-e-eventos',     'EXPENSE', 'music',            'LEISURE_EVENTS',       'c0000000-0000-4000-8000-000000000003'),
    ('c1000000-0000-4000-8000-000000000032', NULL, 'Viagem e hospedagem',        'viagem-e-hospedagem',        'EXPENSE', 'plane',            'LEISURE_TRAVEL',       'c0000000-0000-4000-8000-000000000003'),
    -- Compras
    ('c1000000-0000-4000-8000-000000000033', NULL, 'Roupas e calçados',          'roupas-e-calcados',          'EXPENSE', 'shirt',            'SHOPPING_CLOTHING',    'c0000000-0000-4000-8000-000000000007'),
    ('c1000000-0000-4000-8000-000000000034', NULL, 'Eletrônicos',                'eletronicos',                'EXPENSE', 'smartphone',       'SHOPPING_ELECTRONICS', 'c0000000-0000-4000-8000-000000000007'),
    ('c1000000-0000-4000-8000-000000000035', NULL, 'Marketplace online',         'marketplace-online',         'EXPENSE', 'shopping-bag',     'SHOPPING_ONLINE',      'c0000000-0000-4000-8000-000000000007'),
    ('c1000000-0000-4000-8000-000000000036', NULL, 'Presentes',                  'presentes',                  'EXPENSE', 'gift',             'SHOPPING_GIFTS',       'c0000000-0000-4000-8000-000000000007'),
    ('c1000000-0000-4000-8000-000000000037', NULL, 'Pet',                        'pet',                        'EXPENSE', 'paw-print',        'SHOPPING_PET',         'c0000000-0000-4000-8000-000000000007'),
    -- Impostos e tarifas
    ('c1000000-0000-4000-8000-000000000038', NULL, 'Impostos',                   'impostos',                   'EXPENSE', 'hand-coins',       'FEES_TAX',             'c0000000-0000-4000-8000-000000000014'),
    ('c1000000-0000-4000-8000-000000000039', NULL, 'Tarifas bancárias',          'tarifas-bancarias',          'EXPENSE', 'hand-coins',       'FEES_BANK',            'c0000000-0000-4000-8000-000000000014'),
    ('c1000000-0000-4000-8000-000000000040', NULL, 'Juros e multas',             'juros-e-multas',             'EXPENSE', 'hand-coins',       'FEES_INTEREST',        'c0000000-0000-4000-8000-000000000014'),
    ('c1000000-0000-4000-8000-000000000041', NULL, 'IOF',                        'iof',                        'EXPENSE', 'hand-coins',       'FEES_IOF',             'c0000000-0000-4000-8000-000000000014'),
    -- Receitas
    ('c1000000-0000-4000-8000-000000000042', NULL, 'Salário',                    'salario',                    'INCOME',  'banknote',         'INCOME_SALARY',        'c0000000-0000-4000-8000-000000000009'),
    ('c1000000-0000-4000-8000-000000000043', NULL, 'Renda extra e freelas',      'renda-extra-e-freelas',      'INCOME',  'briefcase',        'INCOME_FREELANCE',     'c0000000-0000-4000-8000-000000000009'),
    ('c1000000-0000-4000-8000-000000000044', NULL, 'Rendimentos e proventos',    'rendimentos-e-proventos',    'INCOME',  'hand-coins',       'INCOME_YIELDS',        'c0000000-0000-4000-8000-000000000009'),
    ('c1000000-0000-4000-8000-000000000045', NULL, 'Cashback e estornos',        'cashback-e-estornos',        'INCOME',  'gift',             'INCOME_CASHBACK',      'c0000000-0000-4000-8000-000000000009'),
    ('c1000000-0000-4000-8000-000000000046', NULL, 'Benefícios e auxílios',      'beneficios-e-auxilios',      'INCOME',  'hand-coins',       'INCOME_BENEFITS',      'c0000000-0000-4000-8000-000000000009'),
    -- Transferências
    ('c1000000-0000-4000-8000-000000000047', NULL, 'Pix',                        'pix',                        'BOTH',    'arrow-left-right', 'TRANSFER_PIX',         'c0000000-0000-4000-8000-000000000010'),
    ('c1000000-0000-4000-8000-000000000048', NULL, 'TED e DOC',                  'ted-e-doc',                  'BOTH',    'arrow-left-right', 'TRANSFER_TED',         'c0000000-0000-4000-8000-000000000010'),
    ('c1000000-0000-4000-8000-000000000049', NULL, 'Boletos e faturas',          'boletos-e-faturas',          'BOTH',    'arrow-left-right', 'TRANSFER_BILLS',       'c0000000-0000-4000-8000-000000000010'),
    ('c1000000-0000-4000-8000-000000000050', NULL, 'Saque e depósito',           'saque-e-deposito',           'BOTH',    'banknote',         'TRANSFER_CASH',        'c0000000-0000-4000-8000-000000000010'),
    ('c1000000-0000-4000-8000-000000000051', NULL, 'Entre contas próprias',      'entre-contas-proprias',      'BOTH',    'arrow-left-right', 'TRANSFER_SELF',        'c0000000-0000-4000-8000-000000000010'),
    -- Investimentos
    ('c1000000-0000-4000-8000-000000000052', NULL, 'Renda fixa',                 'renda-fixa',                 'BOTH',    'piggy-bank',       'INVESTMENT_FIXED',     'c0000000-0000-4000-8000-000000000012'),
    ('c1000000-0000-4000-8000-000000000053', NULL, 'Renda variável',             'renda-variavel',             'BOTH',    'piggy-bank',       'INVESTMENT_VARIABLE',  'c0000000-0000-4000-8000-000000000012'),
    ('c1000000-0000-4000-8000-000000000054', NULL, 'Fundos',                     'fundos',                     'BOTH',    'piggy-bank',       'INVESTMENT_FUNDS',     'c0000000-0000-4000-8000-000000000012'),
    ('c1000000-0000-4000-8000-000000000055', NULL, 'Previdência',                'previdencia',                'BOTH',    'piggy-bank',       'INVESTMENT_PENSION',   'c0000000-0000-4000-8000-000000000012'),
    ('c1000000-0000-4000-8000-000000000056', NULL, 'Cripto',                     'cripto',                     'BOTH',    'piggy-bank',       'INVESTMENT_CRYPTO',    'c0000000-0000-4000-8000-000000000012'),
    ('c1000000-0000-4000-8000-000000000057', NULL, 'Resgates',                   'resgates',                   'BOTH',    'hand-coins',       'INVESTMENT_REDEMPTION','c0000000-0000-4000-8000-000000000012');
