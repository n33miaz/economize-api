package br.com.painel_economico.service.statement.parser;

import java.io.InputStream;
import java.util.List;

public interface StatementParserStrategy {
    StatementFormat format();

    List<ParsedTransaction> parse(InputStream input);
}
