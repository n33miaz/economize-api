package br.com.economize.service.statement.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Slf4j
@Component
public class PdfParser implements StatementParserStrategy {

    private final TxtParser txtParser;

    public PdfParser(TxtParser txtParser) {
        this.txtParser = txtParser;
    }

    @Override
    public StatementFormat format() {
        return StatementFormat.PDF;
    }

    @Override
    public List<ParsedTransaction> parse(InputStream input) {
        try (PDDocument document = Loader.loadPDF(input.readAllBytes())) {
            String text = new PDFTextStripper().getText(document);
            return txtParser.parse(new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler PDF", e);
        }
    }
}
