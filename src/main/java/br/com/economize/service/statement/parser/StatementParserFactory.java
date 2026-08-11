package br.com.economize.service.statement.parser;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class StatementParserFactory {

    private final Map<StatementFormat, StatementParserStrategy> parsers;

    public StatementParserFactory(List<StatementParserStrategy> strategies) {
        this.parsers = strategies.stream()
                .collect(Collectors.toMap(StatementParserStrategy::format, s -> s));
    }

    public StatementParserStrategy resolve(StatementFormat format) {
        StatementParserStrategy parser = parsers.get(format);
        if (parser == null) {
            throw new IllegalArgumentException("Parser não disponível para o formato " + format);
        }
        return parser;
    }
}
