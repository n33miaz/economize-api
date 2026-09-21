package br.com.economize.service.shopping;

import br.com.economize.dto.shopping.ShoppingRequests;
import br.com.economize.dto.shopping.ShoppingResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.FamilyGroup;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.ShoppingItem;
import br.com.economize.model.ShoppingTrip;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.FamilyMemberRepository;
import br.com.economize.repository.ShoppingItemRepository;
import br.com.economize.repository.ShoppingTripRepository;
import br.com.economize.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O carrinho de compras (V39): fusão idempotente por {@code clientId},
 * last-write-wins pelo relógio do aparelho, lápide de remoção, total no
 * servidor, candidatos de conciliação e histórico de preços.
 *
 * <p>O repositório é dublado — a consulta em si (a cláusula "dono OU família",
 * o índice por nome normalizado) é do Postgres, não deste teste; aqui trava a
 * REGRA: quem ganha a fusão, o que soma no total, o que entra na janela de
 * conciliação e como o resumo de preço é calculado.
 */
@ExtendWith(MockitoExtension.class)
class ShoppingTripServiceTest {

    private static final String EMAIL = "dono@economize.test";

    @Mock private ShoppingTripRepository tripRepository;
    @Mock private ShoppingItemRepository itemRepository;
    @Mock private FamilyMemberRepository memberRepository;
    @Mock private BankTransactionRepository transactionRepository;
    @Mock private UserRepository userRepository;

    private ShoppingTripService service;

    private User user;

    @BeforeEach
    void setUp() {
        service = new ShoppingTripService(tripRepository, itemRepository, memberRepository,
                transactionRepository, userRepository);
        user = User.builder().id(UUID.randomUUID()).name("Ana").email(EMAIL).build();
    }

    private void semCasa() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(memberRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
    }

    private void comCasa(UUID groupId) {
        FamilyGroup group = FamilyGroup.builder().id(groupId).name("Casa").build();
        FamilyMember member = FamilyMember.builder().id(UUID.randomUUID()).group(group).user(user).build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(memberRepository.findByUserId(user.getId())).thenReturn(Optional.of(member));
    }

    private ShoppingItem item(String clientId, BigDecimal qty, BigDecimal price, boolean checked, boolean deleted,
                              OffsetDateTime clientUpdatedAt) {
        return ShoppingItem.builder()
                .id(UUID.randomUUID())
                .clientId(clientId)
                .name("Item " + clientId)
                .normalizedName("item " + clientId)
                .quantity(qty)
                .unitPrice(price)
                .checked(checked)
                .deleted(deleted)
                .clientUpdatedAt(clientUpdatedAt)
                .build();
    }

    // ------------------------------------------------------------ fusão / LWW / tombstone

    @Test
    @DisplayName("Item novo entra e fica marcado com quem o colocou no carrinho")
    void itemNovoEntra() {
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID()).build();
        when(itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId())).thenReturn(List.of());
        UUID editor = UUID.randomUUID();

        ShoppingRequests.UpsertItem incoming = new ShoppingRequests.UpsertItem(
                "i1", "Arroz Tio João 5kg", new BigDecimal("2"), new BigDecimal("25.90"),
                "leve 3 pague 2", true, null, false, OffsetDateTime.now(ZoneOffset.UTC));

        ShoppingTripService.MergeOutcome outcome = service.mergeItems(trip, List.of(incoming), editor);

        assertThat(outcome.written()).isEqualTo(1);
        ShoppingItem saved = outcome.items().get(0);
        assertThat(saved.getAddedBy()).isEqualTo(editor);
        // sem acento, minúsculo, espaços colapsados — a chave do histórico
        assertThat(saved.getNormalizedName()).isEqualTo("arroz tio joao 5kg");
        verify(itemRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("Na fusão, o relógio do aparelho MAIS NOVO vence")
    void maisNovoVence() {
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID()).build();
        OffsetDateTime t1 = OffsetDateTime.parse("2026-09-20T10:00:00Z");
        OffsetDateTime t2 = t1.plusMinutes(5);
        ShoppingItem gravado = item("i1", new BigDecimal("1"), new BigDecimal("10.00"), true, false, t1);
        when(itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId())).thenReturn(List.of(gravado));

        ShoppingRequests.UpsertItem incoming = new ShoppingRequests.UpsertItem(
                "i1", "Arroz", new BigDecimal("1"), new BigDecimal("12.00"), null, true, null, false, t2);

        ShoppingTripService.MergeOutcome outcome = service.mergeItems(trip, List.of(incoming), UUID.randomUUID());

        assertThat(outcome.written()).isEqualTo(1);
        assertThat(gravado.getUnitPrice()).isEqualByComparingTo("12.00");
        assertThat(gravado.getClientUpdatedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Reenvio com o MESMO relógio (ou mais velho) não sobrescreve — idempotência")
    void reenvioNaoSobrescreve() {
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID()).build();
        OffsetDateTime t2 = OffsetDateTime.parse("2026-09-20T10:05:00Z");
        OffsetDateTime t1Antigo = t2.minusMinutes(5);
        ShoppingItem gravado = item("i1", new BigDecimal("1"), new BigDecimal("12.00"), true, false, t2);
        when(itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId())).thenReturn(List.of(gravado));

        // reenvio da MESMA edição (rede caiu no meio) — preço antigo, relógio igual
        ShoppingRequests.UpsertItem reenvio = new ShoppingRequests.UpsertItem(
                "i1", "Arroz", new BigDecimal("1"), new BigDecimal("999.99"), null, true, null, false, t2);
        // uma edição mais VELHA chegando atrasada — não pode reverter a mais nova
        ShoppingRequests.UpsertItem atrasado = new ShoppingRequests.UpsertItem(
                "i1", "Arroz", new BigDecimal("1"), new BigDecimal("1.00"), null, true, null, false, t1Antigo);

        ShoppingTripService.MergeOutcome outcome = service.mergeItems(
                trip, List.of(reenvio, atrasado), UUID.randomUUID());

        assertThat(outcome.written()).isZero();
        assertThat(gravado.getUnitPrice()).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("Lápide: item removido entra marcado, não ressuscita e não some da lista")
    void tombstoneNaoRessuscita() {
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID()).build();
        when(itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId())).thenReturn(List.of());

        ShoppingRequests.UpsertItem removido = new ShoppingRequests.UpsertItem(
                "i9", "Iogurte", BigDecimal.ONE, new BigDecimal("4.50"), null, true, null, true,
                OffsetDateTime.now(ZoneOffset.UTC));

        ShoppingTripService.MergeOutcome outcome = service.mergeItems(trip, List.of(removido), UUID.randomUUID());

        assertThat(outcome.items()).hasSize(1);
        assertThat(outcome.items().get(0).isDeleted()).isTrue();
    }

    // ------------------------------------------------------------ total

    @Test
    @DisplayName("Total soma só o que foi pego, tem preço e não foi removido")
    void totalSomaSoOQuePegouComPrecoENaoRemovido() {
        List<ShoppingItem> items = List.of(
                item("a", new BigDecimal("2"), new BigDecimal("10.00"), true, false, OffsetDateTime.now()),
                // não pegou (viu e deixou): fica fora do total
                item("b", new BigDecimal("1"), new BigDecimal("50.00"), false, false, OffsetDateTime.now()),
                // removido: fica fora mesmo estando marcado como pego
                item("c", new BigDecimal("3"), new BigDecimal("5.00"), true, true, OffsetDateTime.now()),
                // sem preço ainda: fica fora
                item("d", new BigDecimal("1"), null, true, false, OffsetDateTime.now()));

        BigDecimal total = ShoppingTripService.total(items);

        assertThat(total).isEqualByComparingTo("20.00");
    }

    // ------------------------------------------------------------ candidatos de conciliação

    private BankTransaction tx(BigDecimal amount, OffsetDateTime date, String type) {
        return BankTransaction.builder().id(UUID.randomUUID()).amount(amount).date(date)
                .type(type).description("Mercado").build();
    }

    @Test
    @DisplayName("Candidatos: janela -1d/+5d, faixa 0,8x-1,2x, só DEBIT, sem perna interna/estorno/descarte")
    void candidatosFiltraFaixaETipo() {
        OffsetDateTime started = OffsetDateTime.parse("2026-09-10T12:00:00Z");
        OffsetDateTime closed = OffsetDateTime.parse("2026-09-10T18:00:00Z");
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID()).userId(user.getId())
                .clientId("c1").startedAt(started).closedAt(closed).build();
        semCasa();
        when(tripRepository.findByUserIdAndClientId(user.getId(), "c1")).thenReturn(Optional.of(trip));
        // referência: soma dos itens = 100.00 (sem nota oficial informada)
        List<ShoppingItem> items = List.of(
                item("i1", BigDecimal.ONE, new BigDecimal("100.00"), true, false, OffsetDateTime.now()));
        when(itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId())).thenReturn(items);

        BankTransaction exato = tx(new BigDecimal("-100.00"), started, "DEBIT");
        BankTransaction dentroDaFaixaBaixa = tx(new BigDecimal("-80.00"), started, "DEBIT");
        BankTransaction foraDaFaixaBaixa = tx(new BigDecimal("-79.00"), started, "DEBIT");
        BankTransaction dentroDaFaixaAlta = tx(new BigDecimal("-120.00"), started, "DEBIT");
        BankTransaction foraDaFaixaAlta = tx(new BigDecimal("-121.00"), started, "DEBIT");
        BankTransaction credito = tx(new BigDecimal("-100.00"), started, "CREDIT");
        BankTransaction interna = tx(new BigDecimal("-100.00"), started, "DEBIT");
        interna.setInternalTransfer(true);
        BankTransaction estornada = tx(new BigDecimal("-100.00"), started, "DEBIT");
        estornada.setRefunded(true);
        BankTransaction descartada = tx(new BigDecimal("-100.00"), started, "DEBIT");
        descartada.setIgnored(true);

        when(transactionRepository.findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                eq(user.getId()), eq(started.minusDays(1)), eq(closed.plusDays(5))))
                .thenReturn(List.of(exato, dentroDaFaixaBaixa, foraDaFaixaBaixa, dentroDaFaixaAlta,
                        foraDaFaixaAlta, credito, interna, estornada, descartada));

        List<ShoppingResponses.ReconcileCandidate> candidates = service.reconcileCandidates(EMAIL, "c1");

        assertThat(candidates).extracting(ShoppingResponses.ReconcileCandidate::id)
                .containsExactlyInAnyOrder(exato.getId(), dentroDaFaixaBaixa.getId(), dentroDaFaixaAlta.getId());
        // o casamento exato vem primeiro — é o de menor diferença
        assertThat(candidates.get(0).id()).isEqualTo(exato.getId());
        assertThat(candidates.get(0).difference()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Candidatos: no máximo 10, do mais próximo em valor para o mais distante")
    void candidatosLimitaA10EOrdenaPorProximidade() {
        OffsetDateTime started = OffsetDateTime.parse("2026-09-10T12:00:00Z");
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID()).userId(user.getId())
                .clientId("c1").startedAt(started).closedAt(started).build();
        semCasa();
        when(tripRepository.findByUserIdAndClientId(user.getId(), "c1")).thenReturn(Optional.of(trip));
        when(itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId())).thenReturn(List.of(
                item("i1", BigDecimal.ONE, new BigDecimal("100.00"), true, false, OffsetDateTime.now())));

        // 11 candidatos válidos, diferenças 0..10 — só os 10 mais próximos voltam
        List<BankTransaction> txs = new java.util.ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            txs.add(tx(new BigDecimal(-100 - i), started.plusHours(i), "DEBIT"));
        }
        when(transactionRepository.findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(
                any(), any(), any())).thenReturn(txs);

        List<ShoppingResponses.ReconcileCandidate> candidates = service.reconcileCandidates(EMAIL, "c1");

        assertThat(candidates).hasSize(10);
        assertThat(candidates.get(0).difference()).isEqualByComparingTo("0.00");
        // a diferença de 10 (a pior) fica de fora
        assertThat(candidates).extracting(ShoppingResponses.ReconcileCandidate::difference)
                .noneMatch(diff -> diff.compareTo(new BigDecimal("10.00")) == 0);
    }

    @Test
    @DisplayName("Sem preço anotado não há referência — a lista de candidatos volta vazia")
    void semReferenciaListaVazia() {
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID()).userId(user.getId())
                .clientId("c1").startedAt(OffsetDateTime.now()).build();
        semCasa();
        when(tripRepository.findByUserIdAndClientId(user.getId(), "c1")).thenReturn(Optional.of(trip));
        when(itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId())).thenReturn(List.of());

        assertThat(service.reconcileCandidates(EMAIL, "c1")).isEmpty();
    }

    // ------------------------------------------------------------ reconciliação

    @Test
    @DisplayName("Conciliar exige um DÉBITO — crédito responde 400")
    void conciliarExigeDebito() {
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID()).userId(user.getId())
                .clientId("c1").status(ShoppingTrip.Status.CLOSED).build();
        semCasa();
        when(tripRepository.findByUserIdAndClientId(user.getId(), "c1")).thenReturn(Optional.of(trip));
        UUID txId = UUID.randomUUID();
        BankTransaction credito = BankTransaction.builder().id(txId).type("CREDIT")
                .amount(new BigDecimal("100")).date(OffsetDateTime.now()).build();
        when(transactionRepository.findByIdAndUserId(txId, user.getId())).thenReturn(Optional.of(credito));

        assertThatThrownBy(() -> service.reconcile(EMAIL, "c1", new ShoppingRequests.ReconcileTrip(txId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("débito");
    }

    @Test
    @DisplayName("Lançamento de outra pessoa responde 404, não 403")
    void lancamentoAlheioE404() {
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID()).userId(user.getId())
                .clientId("c1").status(ShoppingTrip.Status.CLOSED).build();
        semCasa();
        when(tripRepository.findByUserIdAndClientId(user.getId(), "c1")).thenReturn(Optional.of(trip));
        UUID txId = UUID.randomUUID();
        when(transactionRepository.findByIdAndUserId(txId, user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reconcile(EMAIL, "c1", new ShoppingRequests.ReconcileTrip(txId)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Conciliar amarra o lançamento, fecha se ainda aberta e usa o valor como total quando faltava")
    void conciliarAmarraEFecha() {
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID()).userId(user.getId())
                .clientId("c1").status(ShoppingTrip.Status.OPEN).build();
        semCasa();
        when(tripRepository.findByUserIdAndClientId(user.getId(), "c1")).thenReturn(Optional.of(trip));
        when(tripRepository.save(any(ShoppingTrip.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId())).thenReturn(List.of());
        UUID txId = UUID.randomUUID();
        OffsetDateTime dataDoDebito = OffsetDateTime.parse("2026-09-11T09:00:00Z");
        BankTransaction debito = BankTransaction.builder().id(txId).type("DEBIT")
                .amount(new BigDecimal("-153.40")).date(dataDoDebito).build();
        when(transactionRepository.findByIdAndUserId(txId, user.getId())).thenReturn(Optional.of(debito));

        ShoppingResponses.TripItem resultado =
                service.reconcile(EMAIL, "c1", new ShoppingRequests.ReconcileTrip(txId));

        assertThat(resultado.status()).isEqualTo("RECONCILED");
        assertThat(resultado.reconciledTransactionId()).isEqualTo(txId);
        assertThat(resultado.closedAt()).isEqualTo(dataDoDebito);
        assertThat(resultado.receiptTotal()).isEqualByComparingTo("153.40");
    }

    // ------------------------------------------------------------ histórico de preços

    private ShoppingItemRepository.PriceObservation observacao(ShoppingItem item, ShoppingTrip trip) {
        return new ShoppingItemRepository.PriceObservation() {
            @Override public ShoppingItem getItem() { return item; }
            @Override public ShoppingTrip getTrip() { return trip; }
        };
    }

    @Test
    @DisplayName("Nome vazio (ou só espaço/símbolo) não tem o que buscar")
    void nomeVazioNaoBusca() {
        // a validação do nome vem ANTES de ir buscar o usuário — nenhum
        // repositório chega a ser consultado
        assertThatThrownBy(() -> service.priceHistory(EMAIL, "   ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Resumo: último preço é o mais recente; min/max/avg batem com as ocorrências devolvidas")
    void resumoDoHistorico() {
        semCasa();
        ShoppingTrip viagem1 = ShoppingTrip.builder().id(UUID.randomUUID()).clientId("v1")
                .storeName("Mercado A").startedAt(OffsetDateTime.parse("2026-09-15T10:00:00Z")).build();
        ShoppingTrip viagem2 = ShoppingTrip.builder().id(UUID.randomUUID()).clientId("v2")
                .storeName("Mercado B").startedAt(OffsetDateTime.parse("2026-08-01T10:00:00Z")).build();
        // a consulta já devolve do mais recente para o mais antigo
        ShoppingItem recente = item("x", BigDecimal.ONE, new BigDecimal("10.00"), true, false, OffsetDateTime.now());
        ShoppingItem antigo = item("y", BigDecimal.ONE, new BigDecimal("8.00"), true, false, OffsetDateTime.now());
        when(itemRepository.findPriceHistory(eq(user.getId()), any(), eq("arroz"), eq(true), eq(""), any()))
                .thenReturn(List.of(observacao(recente, viagem1), observacao(antigo, viagem2)));

        ShoppingResponses.PriceHistory history = service.priceHistory(EMAIL, "Arroz", null, null);

        assertThat(history.history()).hasSize(2);
        assertThat(history.summary().lastPrice()).isEqualByComparingTo("10.00");
        assertThat(history.summary().lastStore()).isEqualTo("Mercado A");
        assertThat(history.summary().minPrice()).isEqualByComparingTo("8.00");
        assertThat(history.summary().maxPrice()).isEqualByComparingTo("10.00");
        assertThat(history.summary().avgPrice()).isEqualByComparingTo("9.00");
        assertThat(history.summary().occurrences()).isEqualTo(2);
    }

    @Test
    @DisplayName("Sem ocorrência nenhuma, o resumo é NULO — não zero")
    void semOcorrenciaResumoNulo() {
        semCasa();
        when(itemRepository.findPriceHistory(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(List.of());

        ShoppingResponses.PriceHistory history = service.priceHistory(EMAIL, "Produto Raro", null, null);

        assertThat(history.summary()).isNull();
        assertThat(history.history()).isEmpty();
    }

    @Test
    @DisplayName("Store filtra: o nome da loja chega em minúsculas ao repositório")
    void storeFiltraEmMinusculas() {
        semCasa();
        when(itemRepository.findPriceHistory(any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(List.of());

        service.priceHistory(EMAIL, "Arroz", "Mercado ABC", null);

        ArgumentCaptor<String> storeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> allStoresCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(itemRepository).findPriceHistory(any(), any(), any(), allStoresCaptor.capture(),
                storeCaptor.capture(), any());
        assertThat(allStoresCaptor.getValue()).isFalse();
        assertThat(storeCaptor.getValue()).isEqualTo("mercado abc");
    }

    // ------------------------------------------------------------ nota fiscal

    /** Cupom de SP, 09/2026, CNPJ 12.345.678/0001-95, modelo 65, nº 123456. */
    private static final String CHAVE = "35260912345678000195650010001234561123456788";

    private ShoppingTrip compraAbertaDoDono(String clientId) {
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID())
                .userId(user.getId()).clientId(clientId)
                .status(ShoppingTrip.Status.OPEN).build();
        when(tripRepository.findByUserIdAndClientId(user.getId(), clientId))
                .thenReturn(Optional.of(trip));
        // lenient: os casos de chave recusada nem chegam a salvar
        lenient().when(tripRepository.save(any(ShoppingTrip.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return trip;
    }

    @Test
    @DisplayName("A chave do cupom entra na compra, e o CNPJ sai dela sem consultar ninguém")
    void chaveDoCupomEntraComOCnpj() {
        semCasa();
        compraAbertaDoDono("compra1");
        when(tripRepository.findByUserIdAndReceiptKey(user.getId(), CHAVE))
                .thenReturn(Optional.empty());

        ShoppingResponses.TripItem r = service.close(EMAIL, "compra1",
                new ShoppingRequests.CloseTrip(null, CHAVE));

        assertThat(r.receiptKey()).isEqualTo(CHAVE);
        // os 14 dígitos do meio da chave SÃO o CNPJ de quem emitiu
        assertThat(r.receiptIssuerCnpj()).isEqualTo("12345678000195");
    }

    @Test
    @DisplayName("Chave com um dígito trocado é recusada — nota inexistente não se guarda")
    void chaveTortaERecusada() {
        semCasa();
        compraAbertaDoDono("compra2");
        String torta = CHAVE.substring(0, 43) + ((CHAVE.charAt(43) == '9') ? '0' : '9');

        assertThatThrownBy(() -> service.close(EMAIL, "compra2",
                new ShoppingRequests.CloseTrip(null, torta)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não confere");
    }

    @Test
    @DisplayName("A mesma nota em duas compras responde com a frase, não com erro de banco")
    void mesmaNotaDuasVezes() {
        semCasa();
        compraAbertaDoDono("compra3");
        ShoppingTrip outra = ShoppingTrip.builder().id(UUID.randomUUID())
                .userId(user.getId()).clientId("compra-antiga").receiptKey(CHAVE).build();
        when(tripRepository.findByUserIdAndReceiptKey(user.getId(), CHAVE))
                .thenReturn(Optional.of(outra));

        assertThatThrownBy(() -> service.close(EMAIL, "compra3",
                new ShoppingRequests.CloseTrip(null, CHAVE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("já está em outra compra");
    }

    @Test
    @DisplayName("Reenviar a MESMA nota para a MESMA compra não reclama")
    void mesmaNotaNaMesmaCompraPassa() {
        semCasa();
        ShoppingTrip trip = compraAbertaDoDono("compra4");
        // a própria compra já tem a nota: fechar de novo não pode virar erro
        when(tripRepository.findByUserIdAndReceiptKey(user.getId(), CHAVE))
                .thenReturn(Optional.of(trip));

        ShoppingResponses.TripItem r = service.close(EMAIL, "compra4",
                new ShoppingRequests.CloseTrip(null, CHAVE));

        assertThat(r.receiptKey()).isEqualTo(CHAVE);
    }

    @Test
    @DisplayName("Fechar sem nota continua funcionando: a nota pode chegar depois")
    void fecharSemNota() {
        semCasa();
        compraAbertaDoDono("compra5");

        ShoppingResponses.TripItem r = service.close(EMAIL, "compra5",
                new ShoppingRequests.CloseTrip(new java.math.BigDecimal("604.91"), null));

        assertThat(r.receiptKey()).isNull();
        assertThat(r.receiptTotal()).isEqualByComparingTo("604.91");
    }

    // ------------------------------------------------------------ autorização

    @Test
    @DisplayName("Compra que não é do dono nem da família responde 404")
    void compraInvisivelE404() {
        semCasa();
        when(tripRepository.findByUserIdAndClientId(user.getId(), "alheia")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(EMAIL, "alheia", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Membro da família enxerga a compra compartilhada por outro membro")
    void membroDaFamiliaEnxergaCompraCompartilhada() {
        UUID groupId = UUID.randomUUID();
        comCasa(groupId);
        ShoppingTrip compartilhada = ShoppingTrip.builder().id(UUID.randomUUID())
                .userId(UUID.randomUUID()).familyGroupId(groupId).clientId("familia1")
                .status(ShoppingTrip.Status.OPEN).build();
        when(tripRepository.findByUserIdAndClientId(user.getId(), "familia1")).thenReturn(Optional.empty());
        when(tripRepository.findFirstByFamilyGroupIdAndClientIdOrderByCreatedAtAsc(groupId, "familia1"))
                .thenReturn(Optional.of(compartilhada));
        when(tripRepository.save(any(ShoppingTrip.class))).thenAnswer(inv -> inv.getArgument(0));

        ShoppingResponses.TripItem resultado = service.close(EMAIL, "familia1", null);

        assertThat(resultado.status()).isEqualTo("CLOSED");
    }

    // ------------------------------------------------------------ upsert / shareWithFamily

    @Test
    @DisplayName("Sem casa, marcar shareWithFamily=true responde 400 — não há com quem compartilhar")
    void compartilharSemCasaE400() {
        semCasa();
        when(tripRepository.findByUserIdAndClientId(user.getId(), "c1")).thenReturn(Optional.empty());

        ShoppingRequests.UpsertTrip request = new ShoppingRequests.UpsertTrip(
                null, "Mercado X", null, null, null, null, null, null, null, true, List.of());

        assertThatThrownBy(() -> service.upsert(EMAIL, "c1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("casa");
    }

    @Test
    @DisplayName("Só o DONO decide compartilhar — membro reenviando a marca não altera nada")
    void soDonoAlteraCompartilhamento() {
        UUID groupId = UUID.randomUUID();
        comCasa(groupId);
        UUID donoId = UUID.randomUUID();
        ShoppingTrip trip = ShoppingTrip.builder().id(UUID.randomUUID()).userId(donoId)
                .familyGroupId(groupId).clientId("c1").status(ShoppingTrip.Status.OPEN).build();
        when(tripRepository.findByUserIdAndClientId(user.getId(), "c1")).thenReturn(Optional.empty());
        when(tripRepository.findFirstByFamilyGroupIdAndClientIdOrderByCreatedAtAsc(groupId, "c1"))
                .thenReturn(Optional.of(trip));
        when(tripRepository.save(any(ShoppingTrip.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId())).thenReturn(List.of());

        ShoppingRequests.UpsertTrip request = new ShoppingRequests.UpsertTrip(
                null, null, null, null, null, null, null, null, null, false, List.of());

        service.upsert(EMAIL, "c1", request);

        assertThat(trip.getFamilyGroupId()).isEqualTo(groupId);
    }

    @Test
    @DisplayName("Identificador do corpo diferente do da rota é 400")
    void identificadorDivergenteE400() {
        ShoppingRequests.UpsertTrip request = new ShoppingRequests.UpsertTrip(
                "outro", null, null, null, null, null, null, null, null, null, List.of());

        assertThatThrownBy(() -> service.upsert(EMAIL, "c1", request))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
