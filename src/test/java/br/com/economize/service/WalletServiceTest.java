package br.com.economize.service;

import br.com.economize.dto.wallet.TransactionRequest;
import br.com.economize.dto.wallet.TransactionResponse;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.Transaction;
import br.com.economize.model.User;
import br.com.economize.repository.TransactionRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A carteira de investimentos (EC-044/EC-155).
 *
 * <p>O serviço não tinha teste nenhum, e é dele a cláusula de dono da exclusão
 * — a que decide se apagar a operação de outra pessoa responde 404 (ela não
 * existe para você) ou 403 (ela existe, mas não é sua). A segunda resposta
 * confirma a existência de recurso alheio para quem tem qualquer token.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WalletService — as operações da carteira")
class WalletServiceTest {

    private static final String EMAIL = "dono@economize.test";

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private WalletService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).email(EMAIL).name("Dono").build();
    }

    private Transaction operacao(String ativo, String tipo) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .user(user)
                .assetCode(ativo)
                .type(tipo)
                .quantity(new BigDecimal("100"))
                .priceAtTransaction(new BigDecimal("38.42"))
                .transactionDate(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private TransactionRequest pedido(String ativo, String tipo) {
        TransactionRequest request = new TransactionRequest();
        request.setAssetCode(ativo);
        request.setType(tipo);
        request.setQuantity(new BigDecimal("100"));
        request.setPriceAtTransaction(new BigDecimal("38.42"));
        return request;
    }

    // ------------------------------------------------------------- listagem

    @Test
    @DisplayName("Lista as operações do dono, já mapeadas para a resposta")
    void listaAsOperacoesDoDono() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(transactionRepository.findAllByUserIdOrderByTransactionDateDesc(user.getId()))
                .thenReturn(List.of(operacao("PETR4", "BUY"), operacao("VALE3", "SELL")));

        StepVerifier.create(service.getUserTransactions(EMAIL).collectList())
                .assertNext(lista -> {
                    assertThat(lista).hasSize(2);
                    assertThat(lista).extracting(TransactionResponse::getAssetCode)
                            .containsExactly("PETR4", "VALE3");
                    assertThat(lista.get(0).getQuantity()).isEqualByComparingTo("100");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Usuário desconhecido não devolve lista vazia: é erro")
    void usuarioDesconhecidoNaListagem() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        StepVerifier.create(service.getUserTransactions(EMAIL))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // ------------------------------------------------------------- registro

    @Test
    @DisplayName("Grava a operação amarrada ao dono do token")
    void gravaAOperacaoDoDono() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        StepVerifier.create(service.addTransaction(EMAIL, pedido("PETR4", "BUY")))
                .assertNext(resposta -> assertThat(resposta.getAssetCode()).isEqualTo("PETR4"))
                .verifyComplete();

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        // O dono vem do TOKEN, nunca do corpo da requisição
        assertThat(captor.getValue().getUser().getId()).isEqualTo(user.getId());
        assertThat(captor.getValue().getType()).isEqualTo("BUY");
    }

    // -------------------------------------------------------------- exclusão

    @Test
    @DisplayName("Apaga a própria operação")
    void apagaAPropriaOperacao() {
        Transaction minha = operacao("PETR4", "BUY");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(transactionRepository.findByIdAndUserId(minha.getId(), user.getId()))
                .thenReturn(Optional.of(minha));

        StepVerifier.create(service.deleteTransaction(EMAIL, minha.getId())).verifyComplete();

        verify(transactionRepository).delete(minha);
    }

    @Test
    @DisplayName("Operação de outro usuário responde NÃO ENCONTRADA, e nada é apagado")
    void operacaoAlheiaNaoEEncontrada() {
        UUID alheia = UUID.randomUUID();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        // A cláusula de dono está na CONSULTA: id de outro usuário não volta
        when(transactionRepository.findByIdAndUserId(alheia, user.getId()))
                .thenReturn(Optional.empty());

        StepVerifier.create(service.deleteTransaction(EMAIL, alheia))
                .expectError(ResourceNotFoundException.class)
                .verify();

        // 404 e não 403: um 403 confirmaria que aquele id existe
        verify(transactionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("A busca da exclusão passa pelo par (id, dono), nunca só pelo id")
    void buscaSempreComODono() {
        UUID id = UUID.randomUUID();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(transactionRepository.findByIdAndUserId(id, user.getId()))
                .thenReturn(Optional.empty());

        StepVerifier.create(service.deleteTransaction(EMAIL, id)).verifyError();

        // Buscar por id solto e comparar o e-mail depois foi como nasceu o
        // IDOR do EC-037: o dono é filtro, não checagem posterior
        verify(transactionRepository, never()).findById(any());
        verify(transactionRepository).findByIdAndUserId(id, user.getId());
    }
}
