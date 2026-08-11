package br.com.economize.service.statement.parser;

public enum StatementFormat {
    OFX,
    CSV,
    XLSX,
    PDF,
    TXT;

    public static StatementFormat fromFilename(String filename) {
        if (filename == null) throw new IllegalArgumentException("Arquivo sem nome");
        String lower = filename.toLowerCase();
        if (lower.endsWith(".ofx")) return OFX;
        if (lower.endsWith(".csv")) return CSV;
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return XLSX;
        if (lower.endsWith(".pdf")) return PDF;
        if (lower.endsWith(".txt")) return TXT;
        throw new IllegalArgumentException("Formato não suportado: " + filename);
    }
}
