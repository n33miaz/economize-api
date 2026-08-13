-- "Último acesso" da tela de informações do usuário; atualizado a cada login
ALTER TABLE users
    ADD COLUMN last_login_at TIMESTAMPTZ;
