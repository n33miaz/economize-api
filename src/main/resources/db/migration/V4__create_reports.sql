-- EC-019: módulo de Relatórios consolidados (semanais / mensais / anuais)
CREATE TABLE IF NOT EXISTS reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    period VARCHAR(16) NOT NULL,
    start_date TIMESTAMP WITH TIME ZONE NOT NULL,
    end_date TIMESTAMP WITH TIME ZONE NOT NULL,
    total_income DECIMAL(19, 4) NOT NULL DEFAULT 0,
    total_expense DECIMAL(19, 4) NOT NULL DEFAULT 0,
    dominant_category VARCHAR(32),
    summary TEXT,
    categories_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reports_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_reports_user_period ON reports(user_id, period);
CREATE INDEX IF NOT EXISTS idx_reports_window ON reports(user_id, start_date, end_date);
