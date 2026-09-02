package br.com.economize.repository;

import br.com.economize.model.BankTransaction;
import br.com.economize.model.RecurringSeries;
import br.com.economize.model.RecurringSeriesLink;
import br.com.economize.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A liberação de vínculos da série órfã (EC-111) rodando de verdade.
 *
 * <p>A varredura apaga os vínculos da série que não reencontrou e, na MESMA
 * transação, insere vínculos novos para as mesmas transações na série que as
 * explica hoje. {@code bank_transaction_id} é UNIQUE, e o Hibernate executa os
 * INSERTs antes dos DELETEs pendentes no flush — com {@code remove()} entidade
 * a entidade o insert estouraria. O bulk delete em JPQL roda na hora; é isso que
 * este teste prova contra banco, porque nenhum mock reproduz a ordem do flush.
 */
@DataJpaTest
@DisplayName("RecurringSeriesLinkRepository contra banco (EC-111)")
class RecurringSeriesLinkRepositoryTest {

    private static final OffsetDateTime JUNHO_9 =
            OffsetDateTime.of(2026, 6, 9, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private RecurringSeriesLinkRepository linkRepository;

    @Autowired
    private RecurringSeriesRepository seriesRepository;

    @Autowired
    private BankTransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    private User dono;
    private RecurringSeries orfa;
    private RecurringSeries gemea;
    private BankTransaction primeira;
    private BankTransaction segunda;

    @BeforeEach
    void setUp() {
        dono = userRepository.save(User.builder()
                .name("Dono Teste").email("dono@economize.test").password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).build());
        orfa = seriesRepository.save(serie("inter"));
        gemea = seriesRepository.save(serie("cashback"));
        primeira = transacao("2.40", JUNHO_9);
        segunda = transacao("2.40", JUNHO_9.plusMonths(1));
        linkRepository.saveAll(List.of(vinculo(orfa, primeira), vinculo(orfa, segunda)));
        linkRepository.flush();
    }

    @Test
    @DisplayName("Libera os vínculos da órfã e revincula as mesmas transações no mesmo flush")
    void liberaERevinculaSemViolarOUnique() {
        int liberados = linkRepository.deleteAllBySeriesIdIn(Set.of(orfa.getId()));
        linkRepository.saveAll(List.of(vinculo(gemea, primeira), vinculo(gemea, segunda)));
        linkRepository.flush();
        em.clear();

        assertThat(liberados).isEqualTo(2);
        assertThat(linkRepository.findAllBySeriesIdIn(Set.of(orfa.getId()))).isEmpty();
        assertThat(linkRepository.findAllBySeriesIdIn(Set.of(gemea.getId())))
                .extracting(RecurringSeriesLink::getBankTransactionId)
                .containsExactlyInAnyOrder(primeira.getId(), segunda.getId());
    }

    @Test
    @DisplayName("Só apaga os vínculos das séries pedidas")
    void naoTocaVinculoDeOutraSerie() {
        BankTransaction terceira = transacao("9.90", JUNHO_9.plusDays(3));
        linkRepository.saveAndFlush(vinculo(gemea, terceira));

        int liberados = linkRepository.deleteAllBySeriesIdIn(Set.of(orfa.getId()));
        em.clear();

        assertThat(liberados).isEqualTo(2);
        assertThat(linkRepository.findAllBySeriesIdIn(Set.of(gemea.getId())))
                .extracting(RecurringSeriesLink::getBankTransactionId)
                .containsExactly(terceira.getId());
    }

    private RecurringSeries serie(String merchantKey) {
        return RecurringSeries.builder()
                .user(dono)
                .merchantKey(merchantKey)
                .flow(RecurringSeries.Flow.INCOME)
                .cadence(RecurringSeries.Cadence.MONTHLY)
                .amountType(RecurringSeries.AmountType.FIXED)
                .expectedAmount(new BigDecimal("2.4000"))
                .occurrences(2)
                .active(true)
                .dismissed(false)
                .source(RecurringSeries.Source.DETECTED)
                .build();
    }

    private BankTransaction transacao(String valor, OffsetDateTime quando) {
        return transactionRepository.save(BankTransaction.builder()
                .user(dono)
                .transactionId("ext-" + UUID.randomUUID())
                .type("CREDIT")
                .amount(new BigDecimal(valor))
                .description("Cashback")
                .date(quando)
                .reviewStatus(BankTransaction.ReviewStatus.CONFIRMED)
                .internalTransfer(false)
                .build());
    }

    private RecurringSeriesLink vinculo(RecurringSeries series, BankTransaction transaction) {
        return RecurringSeriesLink.builder()
                .seriesId(series.getId())
                .bankTransactionId(transaction.getId())
                .build();
    }
}
