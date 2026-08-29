package br.com.economize.service;

import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionAliasServiceTest {

    private static final String EMAIL = "ana@economize.dev";

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TransactionAliasService service;

    private final User user = User.builder()
            .id(UUID.randomUUID()).name("Ana").email(EMAIL).password("x").build();

    @Test
    void renameStoresTrimmedAliasAndKeepsTheBankDescription() {
        BankTransaction tx = transaction();
        stubOwned(tx);

        BankTransaction renamed = service.rename(EMAIL, tx.getId(), "  Academia  ");

        assertThat(renamed.getDisplayAlias()).isEqualTo("Academia");
        // o nome verdadeiro do banco continua lá: é ele que o modal mostra ao
        // lado do apelido e o que o usuário confere no app do banco
        assertThat(renamed.getDescription()).isEqualTo("PAG*FITMAX 4321 SAO PAULO BRA");
        assertThat(renamed.displayDescription()).isEqualTo("Academia");
        verify(bankTransactionRepository).save(tx);
    }

    @Test
    void blankAliasClearsItAndTheTransactionGoesBackToTheBankDescription() {
        BankTransaction tx = transaction();
        tx.setDisplayAlias("Academia");
        stubOwned(tx);

        BankTransaction cleared = service.rename(EMAIL, tx.getId(), "   ");

        assertThat(cleared.getDisplayAlias()).isNull();
        assertThat(cleared.displayDescription()).isEqualTo("PAG*FITMAX 4321 SAO PAULO BRA");
    }

    @Test
    void nullAliasAlsoClearsIt() {
        BankTransaction tx = transaction();
        tx.setDisplayAlias("Academia");
        stubOwned(tx);

        assertThat(service.rename(EMAIL, tx.getId(), null).getDisplayAlias()).isNull();
    }

    @Test
    void controlCharactersBecomeSpacesAndNeverReachThePersistence() {
        BankTransaction tx = transaction();
        stubOwned(tx);

        // NUL dentro de texto é recusado pelo Postgres (500 em produção que o
        // H2 dos testes não reproduz) — o saneamento o converte antes do banco
        BankTransaction renamed = service.rename(EMAIL, tx.getId(), "abc\0\0def");

        assertThat(renamed.getDisplayAlias()).isEqualTo("abc def");
    }

    @Test
    void invisibleOnlyAliasClearsInsteadOfStoringAnUnreadableLabel() {
        BankTransaction tx = transaction();
        tx.setDisplayAlias("Academia");
        stubOwned(tx);

        // zero-width space não é whitespace para isBlank(): sem saneamento a
        // transação ficaria com descrição invisível em toda listagem
        BankTransaction cleared = service.rename(EMAIL, tx.getId(), "\u200B\uFEFF");

        assertThat(cleared.getDisplayAlias()).isNull();
        assertThat(cleared.displayDescription()).isEqualTo("PAG*FITMAX 4321 SAO PAULO BRA");
    }

    @Test
    void lineBreaksFlattenToASingleLineLabel() {
        BankTransaction tx = transaction();
        stubOwned(tx);

        BankTransaction renamed = service.rename(EMAIL, tx.getId(), "linha1\nlinha2\r\nlinha3");

        assertThat(renamed.getDisplayAlias()).isEqualTo("linha1 linha2 linha3");
    }

    @Test
    void oversizedAliasIsRejectedByTheServiceItselfAndNeverWritten() {
        BankTransaction tx = transaction();
        stubOwned(tx);

        // o @Size do DTO protege só a rota; o limite do VARCHAR(80) precisa
        // valer para qualquer chamador futuro do service
        assertThatThrownBy(() -> service.rename(EMAIL, tx.getId(), "a".repeat(81)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Apelido deve ter no máximo 80 caracteres");
        verify(bankTransactionRepository, never()).save(any());
    }

    @Test
    void aliasNeverBecomesEngineInput() {
        BankTransaction tx = transaction();
        stubOwned(tx);

        service.rename(EMAIL, tx.getId(), "Academia");

        // a chave normalizada é o insumo das regras aprendidas, da reconciliação
        // de importação e do agrupamento da revisão: renomear não a recalcula
        assertThat(tx.getNormalizedDescription()).isEqualTo("fitmax");
        // e a categorização já decidida também não é revista pelo apelido
        assertThat(tx.getCategoryId()).isNotNull();
        assertThat(tx.getReviewStatus()).isEqualTo(BankTransaction.ReviewStatus.SUGGESTED);
        assertThat(tx.getCategorizedBy()).isEqualTo(BankTransaction.CategorizedBy.KEYWORD);
        assertThat(tx.getConfidence()).isEqualByComparingTo("0.70");
    }

    @Test
    void transactionOfAnotherOwnerIsNotFoundAndIsNeverWritten() {
        UUID foreignId = UUID.randomUUID();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        // a consulta já nasce filtrada pelo dono — a transação do vizinho não
        // volta do banco, então não há nada a comparar nem a vazar
        when(bankTransactionRepository.findByIdAndUserId(foreignId, user.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rename(EMAIL, foreignId, "Academia"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Transação não encontrada");
        verify(bankTransactionRepository, never()).save(any());
    }

    private void stubOwned(BankTransaction tx) {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bankTransactionRepository.findByIdAndUserId(tx.getId(), user.getId()))
                .thenReturn(Optional.of(tx));
    }

    private BankTransaction transaction() {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .transactionId(UUID.randomUUID().toString())
                .type("DEBIT")
                .amount(new BigDecimal("-99.90"))
                .description("PAG*FITMAX 4321 SAO PAULO BRA")
                .normalizedDescription("fitmax")
                .categoryId(UUID.randomUUID())
                .reviewStatus(BankTransaction.ReviewStatus.SUGGESTED)
                .categorizedBy(BankTransaction.CategorizedBy.KEYWORD)
                .confidence(new BigDecimal("0.70"))
                .date(OffsetDateTime.now())
                .build();
    }
}
