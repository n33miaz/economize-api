package br.com.economize.service.statement.category;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptionNormalizerTest {

    @Test
    void removesBankNoiseDatesAndLongDigitSequences() {
        assertThat(DescriptionNormalizer.normalize("COMPRA CARTAO 12/07 IFOOD *REST 4321"))
                .isEqualTo("ifood rest");
    }

    @Test
    void stripsAccentsAndLowercases() {
        assertThat(DescriptionNormalizer.normalize("PADARIA PÃO QUENTE LTDA"))
                .isEqualTo("padaria pao quente ltda");
    }

    @Test
    void removesInstallmentMarker() {
        assertThat(DescriptionNormalizer.normalize("NETFLIX PARC 3/12")).isEqualTo("netflix");
        assertThat(DescriptionNormalizer.normalize("MAGAZINE PARCELA 2/6")).isEqualTo("magazine");
    }

    @Test
    void removesLongDigitSequencesButPreservesShortOnes() {
        // números curtos podem ser parte do nome do estabelecimento (ex.: "posto br 123")
        assertThat(DescriptionNormalizer.normalize("POSTO BR 123 98765")).isEqualTo("posto br 123");
    }

    @Test
    void fallsBackToLowercasedKeyWhenCleaningRemovesEverything() {
        assertThat(DescriptionNormalizer.normalize("PIX ENVIADO")).isEqualTo("pix enviado");
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertThat(DescriptionNormalizer.normalize(null)).isEmpty();
        assertThat(DescriptionNormalizer.normalize("   ")).isEmpty();
    }

    @Test
    void truncatesAtMaxLength() {
        String result = DescriptionNormalizer.normalize("mercadinho".repeat(30));

        assertThat(result).hasSize(DescriptionNormalizer.MAX_LENGTH);
    }
}
