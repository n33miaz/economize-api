package br.com.painel_economico.service.statement.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class XlsxParser implements StatementParserStrategy {

    @Override
    public StatementFormat format() {
        return StatementFormat.XLSX;
    }

    @Override
    public List<ParsedTransaction> parse(InputStream input) {
        List<ParsedTransaction> out = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(input)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) return out;
            Map<String, Integer> header = readHeader(sheet.getRow(sheet.getFirstRowNum()));
            int dateCol = pick(header, "data", "date");
            int descCol = pick(header, "descrição", "descricao", "description", "histórico", "historico");
            int amountCol = pick(header, "valor", "amount");
            if (dateCol < 0 || amountCol < 0) {
                throw new IllegalArgumentException("XLSX precisa ter colunas Data e Valor");
            }
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                OffsetDateTime date = readDate(row.getCell(dateCol));
                BigDecimal value = readNumber(row.getCell(amountCol));
                String desc = descCol >= 0 ? readString(row.getCell(descCol)) : "";
                if (date == null || value == null) continue;
                out.add(ParsedTransaction.builder()
                        .externalId("XLSX-" + i + "-" + date)
                        .type(value.signum() >= 0 ? "CREDIT" : "DEBIT")
                        .amount(value)
                        .description(desc != null ? desc : "")
                        .date(date)
                        .build());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler XLSX", e);
        }
        return out;
    }

    private Map<String, Integer> readHeader(Row row) {
        Map<String, Integer> map = new HashMap<>();
        if (row == null) return map;
        for (int i = 0; i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell == null) continue;
            map.put(cell.getStringCellValue().toLowerCase().trim(), i);
        }
        return map;
    }

    private int pick(Map<String, Integer> header, String... keys) {
        for (String key : keys) {
            Integer idx = header.get(key);
            if (idx != null) return idx;
        }
        return -1;
    }

    private OffsetDateTime readDate(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue().toInstant().atOffset(ZoneOffset.UTC);
        }
        return null;
    }

    private BigDecimal readNumber(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING -> {
                try {
                    yield new BigDecimal(cell.getStringCellValue().replace(",", ".").trim());
                } catch (Exception e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    private String readString(Cell cell) {
        if (cell == null) return "";
        cell.setCellType(org.apache.poi.ss.usermodel.CellType.STRING);
        return cell.getStringCellValue();
    }
}
