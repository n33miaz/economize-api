-- file_hash nasceu CHAR(64) na V3, mas o mapeamento JPA declara VARCHAR(64);
-- no PostgreSQL bpchar != varchar e o ddl-auto=validate impede a aplicação
-- de subir. Hashes SHA-256 têm sempre 64 caracteres, então a conversão não
-- altera dado nenhum.
ALTER TABLE statement_uploads
    ALTER COLUMN file_hash TYPE VARCHAR(64);
