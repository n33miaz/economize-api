package br.com.economize.service.shopping;

import br.com.economize.dto.shopping.ShoppingRequests;
import br.com.economize.dto.shopping.ShoppingResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.BankTransaction;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.ShoppingItem;
import br.com.economize.model.ShoppingTrip;
import br.com.economize.model.User;
import br.com.economize.repository.BankTransactionRepository;
import br.com.economize.repository.FamilyMemberRepository;
import br.com.economize.repository.ShoppingItemRepository;
import br.com.economize.repository.ShoppingTripRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.LogSafe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O carrinho de compras (V39): a compra registrada no corredor do mercado,
 * sem internet, fundida no servidor quando a rede volta e compartilhada com
 * a casa.
 *
 * <p><b>A regra que tudo aqui obedece: o aparelho é a fonte, o servidor é o
 * ponto de encontro.</b> A viagem nasce offline com um id do aparelho
 * ({@code clientId}); o {@code PUT} é idempotente por ele, então reenviar a
 * mesma viagem (rede caindo no meio) não duplica nada. Dois aparelhos da
 * mesma casa editam a mesma lista sem se ver, e a fusão é por item: o mais
 * novo pelo relógio do aparelho vence, e item removido vira lápide em vez de
 * sumir — senão o outro aparelho o ressuscitaria no próximo envio.
 *
 * <p><b>Quem vê o quê.</b> A viagem é visível e editável pelo dono e por
 * qualquer membro da casa dele quando ele a compartilhou. O que não é visível
 * responde 404, e não 403 — a regra do projeto desde o EC-037: confirmar que
 * um id existe já é vazar.
 *
 * <p><b>O que ele NÃO faz (v1):</b> não recebe foto (a referência é local ao
 * aparelho), não lê etiqueta nem nota fiscal. A conciliação é com o EXTRATO,
 * que é o que o app já tem: candidatos por janela de datas e faixa de valor,
 * e a pessoa escolhe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoppingTripService {

    static final int DEFAULT_TRIP_LIMIT = 50;
    static final int MAX_TRIP_LIMIT = 200;
    static final int DEFAULT_HISTORY_LIMIT = 20;
    static final int MAX_HISTORY_LIMIT = 100;
    static final int MAX_CANDIDATES = 10;

    /**
     * A janela de conciliação: o lançamento pode ter data de um dia ANTES de
     * a viagem começar (fuso e horário do banco) e até cinco dias DEPOIS do
     * caixa — cartão de débito e Pix caem no mesmo dia, cartão de crédito
     * pode demorar para aparecer no extrato do conector.
     */
    static final Duration CANDIDATE_BEFORE = Duration.ofDays(1);
    static final Duration CANDIDATE_AFTER = Duration.ofDays(5);
    /** A faixa de valor: a soma do corredor erra, mas não erra 20%. */
    static final BigDecimal CANDIDATE_LOWER = new BigDecimal("0.80");
    static final BigDecimal CANDIDATE_UPPER = new BigDecimal("1.20");

    static final String TRIP_NOT_FOUND = "Compra não encontrada";
    static final String TRANSACTION_NOT_FOUND = "Lançamento não encontrado";
    static final String NO_FAMILY_MESSAGE = "Você ainda não faz parte de uma casa — não há com quem compartilhar";
    static final String INVALID_STATUS_MESSAGE = "Status inválido — use OPEN, CLOSED ou RECONCILED";
    static final String INVALID_CLIENT_ID_MESSAGE = "Identificador da compra inválido";

    private static final Set<ShoppingTrip.Status> ALL_STATUSES = EnumSet.allOf(ShoppingTrip.Status.class);
    private static final Set<ShoppingTrip.Status> FINISHED = EnumSet.of(
            ShoppingTrip.Status.CLOSED, ShoppingTrip.Status.RECONCILED);

    private final ShoppingTripRepository tripRepository;
    private final ShoppingItemRepository itemRepository;
    private final FamilyMemberRepository memberRepository;
    private final BankTransactionRepository transactionRepository;
    private final UserRepository userRepository;

    // ------------------------------------------------------------ leitura

    /** As minhas viagens e as da casa, da mais recente para a mais antiga, com os itens. */
    @Transactional(readOnly = true)
    public List<ShoppingResponses.TripItem> list(String email, String status, Integer limit) {
        Viewer viewer = viewer(email);
        Set<ShoppingTrip.Status> statuses = status == null || status.isBlank()
                ? ALL_STATUSES : EnumSet.of(parseStatus(status));
        int size = clamp(limit, DEFAULT_TRIP_LIMIT, MAX_TRIP_LIMIT);

        List<ShoppingTrip> trips = tripRepository.findVisible(
                viewer.userId(), viewer.groupOrSentinel(), statuses, PageRequest.of(0, size));
        Map<UUID, List<ShoppingItem>> itemsByTrip = itemsByTrip(trips);
        Map<UUID, String> names = namesFor(trips, itemsByTrip);
        return trips.stream()
                .map(trip -> toResponse(trip, itemsByTrip.getOrDefault(trip.getId(), List.of()), names))
                .toList();
    }

    @Transactional(readOnly = true)
    public ShoppingResponses.ShoppingSummary summary(String email) {
        Viewer viewer = viewer(email);
        UUID group = viewer.groupOrSentinel();

        long open = tripRepository.countVisibleByStatus(viewer.userId(), group, ShoppingTrip.Status.OPEN);

        ShoppingResponses.LastTrip last = tripRepository
                .findVisibleClosed(viewer.userId(), group, FINISHED, PageRequest.of(0, 1))
                .stream().findFirst()
                .map(trip -> new ShoppingResponses.LastTrip(
                        trip.getClientId(), trip.getStoreName(),
                        spent(trip, itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId())),
                        trip.getClosedAt()))
                .orElse(null);

        // O mês corrente em UTC, como o resto do projeto (ver FamilyController)
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<ShoppingTrip> ofMonth = tripRepository.findVisibleClosedInWindow(
                viewer.userId(), group, FINISHED, start, end);
        Map<UUID, List<ShoppingItem>> itemsByTrip = itemsByTrip(ofMonth);
        BigDecimal monthTotal = ofMonth.stream()
                .map(trip -> spent(trip, itemsByTrip.getOrDefault(trip.getId(), List.of())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        return new ShoppingResponses.ShoppingSummary((int) open, last, monthTotal);
    }

    /**
     * "Quanto isto custou das outras vezes": as últimas ocorrências do nome
     * normalizado nas viagens visíveis, e um resumo delas.
     *
     * <p>O resumo é das ocorrências DEVOLVIDAS (as últimas {@code limit}), não
     * do histórico inteiro: é o preço recente que interessa a quem está na
     * frente da gôndola, e a média de dois anos atrás só atrapalharia.
     */
    @Transactional(readOnly = true)
    public ShoppingResponses.PriceHistory priceHistory(String email, String name, String store, Integer limit) {
        String normalized = ShoppingNames.normalize(name);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Informe o nome do produto");
        }
        Viewer viewer = viewer(email);
        String storeKey = store == null || store.isBlank() ? null : store.trim().toLowerCase(Locale.ROOT);
        int size = clamp(limit, DEFAULT_HISTORY_LIMIT, MAX_HISTORY_LIMIT);

        List<ShoppingItemRepository.PriceObservation> rows = itemRepository.findPriceHistory(
                viewer.userId(), viewer.groupOrSentinel(), normalized,
                storeKey == null, storeKey == null ? "" : storeKey, PageRequest.of(0, size));

        List<ShoppingResponses.PriceOccurrence> occurrences = rows.stream()
                .map(row -> new ShoppingResponses.PriceOccurrence(
                        row.getTrip().getClientId(), row.getTrip().getStoreName(),
                        row.getItem().getUnitPrice(), row.getItem().getQuantity(),
                        row.getItem().getPromoNote(), row.getItem().isChecked(),
                        row.getTrip().getStartedAt()))
                .toList();

        return new ShoppingResponses.PriceHistory(name.trim(), normalized, summarize(occurrences), occurrences);
    }

    static ShoppingResponses.PriceSummary summarize(List<ShoppingResponses.PriceOccurrence> occurrences) {
        if (occurrences.isEmpty()) return null;
        ShoppingResponses.PriceOccurrence last = occurrences.get(0);
        BigDecimal min = null;
        BigDecimal max = null;
        BigDecimal sum = BigDecimal.ZERO;
        for (ShoppingResponses.PriceOccurrence o : occurrences) {
            BigDecimal price = o.unitPrice();
            min = min == null || price.compareTo(min) < 0 ? price : min;
            max = max == null || price.compareTo(max) > 0 ? price : max;
            sum = sum.add(price);
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(occurrences.size()), 2, RoundingMode.HALF_UP);
        return new ShoppingResponses.PriceSummary(
                last.unitPrice(), last.storeName(), last.date(), min, max, avg, occurrences.size());
    }

    // ------------------------------------------------------------ escrita

    /**
     * Cria ou funde a viagem — idempotente por {@code clientId}.
     *
     * <p>Cabeçalho: campo nulo mantém o que está gravado (é uma sincronização,
     * e o aparelho que só acrescentou um item não sabe o resto). Itens: fusão
     * por {@code clientId} com last-write-wins pelo relógio do aparelho. A
     * resposta é a viagem consolidada, com ids do servidor — é o que o
     * aparelho guarda no lugar do que tinha.
     */
    @Transactional
    public ShoppingResponses.TripItem upsert(String email, String pathClientId, ShoppingRequests.UpsertTrip request) {
        String clientId = requireClientId(pathClientId);
        if (request.clientId() != null && !request.clientId().isBlank()
                && !clientId.equals(request.clientId().trim())) {
            throw new IllegalArgumentException("O identificador do corpo não é o da rota");
        }
        Viewer viewer = viewer(email);

        Optional<ShoppingTrip> found = findVisible(viewer, clientId);
        boolean created = found.isEmpty();
        ShoppingTrip trip = found.orElseGet(() -> ShoppingTrip.builder()
                .userId(viewer.userId())
                .clientId(clientId)
                .status(ShoppingTrip.Status.OPEN)
                .build());

        applyHeader(trip, request, viewer);
        trip = tripRepository.save(trip);

        List<ShoppingRequests.UpsertItem> incoming = request.items() == null ? List.of() : request.items();
        MergeOutcome merged = mergeItems(trip, incoming, viewer.userId());
        if (merged.written() > 0 && !created) {
            // A fusão de itens tem que aparecer no updatedAt da viagem — é
            // por ele que o outro aparelho decide se precisa buscar de novo
            trip.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
            trip = tripRepository.save(trip);
        }

        log.info("Compra {} {} por {}: {} itens recebidos, {} gravados",
                LogSafe.value(clientId), created ? "criada" : "fundida", viewer.userId(),
                incoming.size(), merged.written());
        return toResponse(trip, merged.items(), namesFor(List.of(trip), Map.of(trip.getId(), merged.items())));
    }

    /** Passou no caixa. Com o total da nota quando a pessoa já o tem na mão. */
    @Transactional
    public ShoppingResponses.TripItem close(String email, String pathClientId, ShoppingRequests.CloseTrip request) {
        Viewer viewer = viewer(email);
        ShoppingTrip trip = requireVisible(viewer, requireClientId(pathClientId));
        if (trip.getStatus() == ShoppingTrip.Status.RECONCILED) {
            throw new IllegalArgumentException("Esta compra já foi conciliada com o extrato");
        }
        trip.setStatus(ShoppingTrip.Status.CLOSED);
        if (trip.getClosedAt() == null) {
            trip.setClosedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        }
        if (request != null && request.receiptTotal() != null) {
            trip.setReceiptTotal(positiveOrNull(request.receiptTotal()));
        }
        trip = tripRepository.save(trip);
        log.info("Compra {} encerrada por {}", LogSafe.value(trip.getClientId()), viewer.userId());
        return respond(trip);
    }

    /**
     * Amarra a compra ao lançamento do extrato que a pagou.
     *
     * <p>O lançamento tem que ser DE QUEM CONCILIA: na casa, cada um tem o seu
     * extrato, e quem pagou é quem tem a linha. Lançamento alheio responde
     * 404, e não 403 — mesma regra dos demais recursos do projeto.
     */
    @Transactional
    public ShoppingResponses.TripItem reconcile(String email, String pathClientId,
                                                ShoppingRequests.ReconcileTrip request) {
        Viewer viewer = viewer(email);
        ShoppingTrip trip = requireVisible(viewer, requireClientId(pathClientId));
        BankTransaction tx = transactionRepository.findByIdAndUserId(request.transactionId(), viewer.userId())
                .orElseThrow(() -> new ResourceNotFoundException(TRANSACTION_NOT_FOUND));
        if (!"DEBIT".equalsIgnoreCase(tx.getType())) {
            throw new IllegalArgumentException("Só um débito do extrato paga uma compra");
        }

        trip.setStatus(ShoppingTrip.Status.RECONCILED);
        trip.setReconciledTransactionId(tx.getId());
        if (trip.getClosedAt() == null) {
            // conciliar uma viagem ainda aberta é encerrá-la: a data do caixa
            // mais próxima que se tem é a do lançamento
            trip.setClosedAt(tx.getDate());
        }
        if (trip.getReceiptTotal() == null) {
            // O extrato é a evidência mais forte do que foi pago. Sem a nota
            // informada, deixar nulo manteria o total do mês na estimativa
            // do corredor, que é justamente o número que a conciliação corrige
            trip.setReceiptTotal(tx.getAmount().abs());
        }
        trip = tripRepository.save(trip);
        log.info("Compra {} conciliada com lançamento {} por {}",
                LogSafe.value(trip.getClientId()), tx.getId(), viewer.userId());
        return respond(trip);
    }

    /**
     * Os lançamentos DO CHAMADOR que podem ter pago esta compra: débitos na
     * janela de datas, com valor a até 20% da referência (a nota quando
     * informada, senão a soma dos itens), do mais próximo em valor para o
     * mais distante. Sem referência (nenhum preço anotado) não há com o que
     * comparar, e a lista volta vazia.
     */
    @Transactional(readOnly = true)
    public List<ShoppingResponses.ReconcileCandidate> reconcileCandidates(String email, String pathClientId) {
        Viewer viewer = viewer(email);
        ShoppingTrip trip = requireVisible(viewer, requireClientId(pathClientId));
        List<ShoppingItem> items = itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId());
        BigDecimal reference = spent(trip, items);
        if (reference.signum() <= 0) return List.of();

        OffsetDateTime start = trip.getStartedAt().minus(CANDIDATE_BEFORE);
        OffsetDateTime closed = trip.getClosedAt() != null ? trip.getClosedAt() : OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime end = closed.plus(CANDIDATE_AFTER);
        BigDecimal lower = reference.multiply(CANDIDATE_LOWER);
        BigDecimal upper = reference.multiply(CANDIDATE_UPPER);

        return transactionRepository
                .findAllByUserIdAndDateGreaterThanEqualAndDateLessThanOrderByDateDesc(viewer.userId(), start, end)
                .stream()
                .filter(tx -> "DEBIT".equalsIgnoreCase(tx.getType()))
                // perna interna, estorno e linha descartada não pagaram mercado nenhum
                .filter(tx -> !tx.isInternalTransfer() && !tx.isRefunded() && !tx.isIgnored())
                .filter(tx -> {
                    BigDecimal value = tx.getAmount().abs();
                    return value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0;
                })
                .sorted(Comparator
                        .comparing((BankTransaction tx) -> tx.getAmount().abs().subtract(reference).abs())
                        .thenComparing(BankTransaction::getDate, Comparator.reverseOrder()))
                .limit(MAX_CANDIDATES)
                .map(tx -> new ShoppingResponses.ReconcileCandidate(
                        tx.getId(), tx.getDate(), tx.displayDescription(),
                        tx.getAmount().abs().setScale(2, RoundingMode.HALF_UP), tx.getAccountId(),
                        tx.getAmount().abs().subtract(reference).setScale(2, RoundingMode.HALF_UP)))
                .toList();
    }

    // ------------------------------------------------------------ fusão

    /**
     * Cabeçalho da viagem: nulo mantém. As transições de status são as do
     * modelo — RECONCILED não se alcança por aqui (é pela conciliação, que
     * exige o lançamento) e não se desfaz por aqui (já está amarrado a
     * dinheiro que saiu).
     */
    private void applyHeader(ShoppingTrip trip, ShoppingRequests.UpsertTrip r, Viewer viewer) {
        if (r.storeName() != null) trip.setStoreName(blankToNull(r.storeName()));
        if (r.notes() != null) trip.setNotes(blankToNull(r.notes()));
        // zero (ou negativo) é "tirei o teto": nulo já significa "não mexa"
        if (r.budget() != null) trip.setBudget(positiveOrNull(r.budget()));
        if (r.receiptTotal() != null) trip.setReceiptTotal(positiveOrNull(r.receiptTotal()));
        if (r.startedAt() != null) trip.setStartedAt(r.startedAt());
        if (r.closedAt() != null) trip.setClosedAt(r.closedAt());

        if (r.status() != null && !r.status().isBlank()) {
            applyStatus(trip, parseStatus(r.status()), r.closedAt());
        }

        // Só o DONO decide com quem compartilha: o membro que recebeu a viagem
        // pela casa a reenvia com a marca que recebeu, e isso não é decisão
        if (r.shareWithFamily() != null && trip.getUserId().equals(viewer.userId())) {
            if (r.shareWithFamily()) {
                if (viewer.groupId() == null) throw new IllegalArgumentException(NO_FAMILY_MESSAGE);
                trip.setFamilyGroupId(viewer.groupId());
            } else {
                trip.setFamilyGroupId(null);
            }
        }
    }

    private static void applyStatus(ShoppingTrip trip, ShoppingTrip.Status requested, OffsetDateTime closedAt) {
        if (trip.getStatus() == ShoppingTrip.Status.RECONCILED) {
            // amarrada ao extrato: o aparelho que ainda não soube manda o
            // status antigo, e o servidor simplesmente não volta atrás
            return;
        }
        switch (requested) {
            case RECONCILED -> throw new IllegalArgumentException(
                    "Conciliar é pela rota de conciliação, com o lançamento do extrato");
            case CLOSED -> {
                trip.setStatus(ShoppingTrip.Status.CLOSED);
                if (trip.getClosedAt() == null) {
                    trip.setClosedAt(closedAt != null ? closedAt
                            : OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
                }
            }
            case OPEN -> {
                // reabrir: lembrou de um item depois de fechar — a data do
                // caixa deixa de valer
                trip.setStatus(ShoppingTrip.Status.OPEN);
                trip.setClosedAt(null);
            }
        }
    }

    /**
     * A fusão dos itens: por {@code clientId}, o mais novo vence.
     *
     * <p>Item que o servidor não conhece entra (mesmo já como lápide — o
     * aparelho criou e apagou offline, e gravar a lápide é o que faz a
     * resposta confirmar que ele foi visto). Item conhecido só é substituído
     * se o relógio do aparelho for MAIS NOVO que o gravado; igual ou mais
     * velho mantém o do servidor — é o que torna o reenvio idempotente.
     */
    MergeOutcome mergeItems(ShoppingTrip trip, List<ShoppingRequests.UpsertItem> incoming, UUID editorId) {
        Map<String, ShoppingItem> byClientId = new LinkedHashMap<>();
        for (ShoppingItem item : itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId())) {
            byClientId.put(item.getClientId(), item);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        List<ShoppingItem> toSave = new ArrayList<>();

        for (ShoppingRequests.UpsertItem in : incoming) {
            String clientId = in.clientId().trim();
            OffsetDateTime stamp = in.clientUpdatedAt() != null ? in.clientUpdatedAt() : now;
            ShoppingItem current = byClientId.get(clientId);
            if (current == null) {
                ShoppingItem novo = ShoppingItem.builder()
                        .tripId(trip.getId())
                        .clientId(clientId)
                        .addedBy(editorId)
                        .build();
                apply(novo, in, stamp);
                byClientId.put(clientId, novo);
                toSave.add(novo);
            } else if (stamp.isAfter(current.getClientUpdatedAt())) {
                apply(current, in, stamp);
                if (!toSave.contains(current)) toSave.add(current);
            }
        }
        if (!toSave.isEmpty()) {
            itemRepository.saveAll(toSave);
        }
        return new MergeOutcome(new ArrayList<>(byClientId.values()), toSave.size());
    }

    private static void apply(ShoppingItem item, ShoppingRequests.UpsertItem in, OffsetDateTime stamp) {
        String name = in.name().trim();
        item.setName(name);
        item.setNormalizedName(ShoppingNames.normalize(name));
        item.setQuantity(in.quantity() != null ? in.quantity() : BigDecimal.ONE);
        item.setUnitPrice(in.unitPrice());
        item.setPromoNote(blankToNull(in.promoNote()));
        item.setChecked(in.checked() == null || in.checked());
        item.setPhotoRef(blankToNull(in.photoRef()));
        item.setDeleted(in.deleted() != null && in.deleted());
        item.setClientUpdatedAt(stamp);
    }

    record MergeOutcome(List<ShoppingItem> items, int written) {
    }

    // ------------------------------------------------------------ apoio

    /** Soma de quantidade × preço dos itens pegos, com preço e sem lápide. */
    static BigDecimal total(List<ShoppingItem> items) {
        return items.stream()
                .filter(ShoppingItem::countsTowardsTotal)
                .map(item -> item.getQuantity().multiply(item.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** O que saiu de verdade: a nota quando informada, senão a soma do corredor. */
    static BigDecimal spent(ShoppingTrip trip, List<ShoppingItem> items) {
        return trip.getReceiptTotal() != null
                ? trip.getReceiptTotal().setScale(2, RoundingMode.HALF_UP)
                : total(items);
    }

    private ShoppingResponses.TripItem respond(ShoppingTrip trip) {
        List<ShoppingItem> items = itemRepository.findAllByTripIdOrderByCreatedAtAsc(trip.getId());
        return toResponse(trip, items, namesFor(List.of(trip), Map.of(trip.getId(), items)));
    }

    private static ShoppingResponses.TripItem toResponse(ShoppingTrip trip, List<ShoppingItem> items,
                                                         Map<UUID, String> names) {
        List<ShoppingResponses.ItemResponse> lines = items.stream()
                .map(item -> new ShoppingResponses.ItemResponse(
                        item.getId(), item.getClientId(), item.getName(), item.getQuantity(),
                        item.getUnitPrice(),
                        item.getUnitPrice() == null ? null
                                : item.getQuantity().multiply(item.getUnitPrice()).setScale(2, RoundingMode.HALF_UP),
                        item.getPromoNote(), item.isChecked(), item.getPhotoRef(),
                        item.getAddedBy(), names.get(item.getAddedBy()),
                        item.isDeleted(), item.getClientUpdatedAt(), item.getUpdatedAt()))
                .toList();
        int alive = (int) items.stream().filter(item -> !item.isDeleted()).count();
        return new ShoppingResponses.TripItem(
                trip.getId(), trip.getClientId(), trip.getUserId(), names.get(trip.getUserId()),
                trip.getFamilyGroupId(), trip.isShared(), trip.getStoreName(), trip.getStatus().name(),
                trip.getBudget(), trip.getStartedAt(), trip.getClosedAt(), trip.getReceiptTotal(),
                trip.getReconciledTransactionId(), trip.getNotes(), total(items), alive, lines,
                trip.getCreatedAt(), trip.getUpdatedAt());
    }

    private Map<UUID, List<ShoppingItem>> itemsByTrip(List<ShoppingTrip> trips) {
        if (trips.isEmpty()) return Map.of();
        List<UUID> ids = trips.stream().map(ShoppingTrip::getId).toList();
        return itemRepository.findAllByTripIdInOrderByCreatedAtAsc(ids).stream()
                .collect(Collectors.groupingBy(ShoppingItem::getTripId, LinkedHashMap::new, Collectors.toList()));
    }

    /** Os nomes de quem abriu e de quem pegou cada item, numa ida só ao banco. */
    private Map<UUID, String> namesFor(List<ShoppingTrip> trips, Map<UUID, List<ShoppingItem>> itemsByTrip) {
        Set<UUID> ids = new HashSet<>();
        trips.forEach(trip -> ids.add(trip.getUserId()));
        itemsByTrip.values().forEach(items -> items.stream()
                .map(ShoppingItem::getAddedBy).filter(Objects::nonNull).forEach(ids::add));
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> names = new HashMap<>();
        userRepository.findAllById(ids).forEach(user -> names.put(user.getId(), user.getName()));
        return names;
    }

    private Optional<ShoppingTrip> findVisible(Viewer viewer, String clientId) {
        Optional<ShoppingTrip> own = tripRepository.findByUserIdAndClientId(viewer.userId(), clientId);
        if (own.isPresent() || viewer.groupId() == null) return own;
        return tripRepository.findFirstByFamilyGroupIdAndClientIdOrderByCreatedAtAsc(viewer.groupId(), clientId);
    }

    private ShoppingTrip requireVisible(Viewer viewer, String clientId) {
        return findVisible(viewer, clientId)
                .orElseThrow(() -> new ResourceNotFoundException(TRIP_NOT_FOUND));
    }

    private Viewer viewer(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        UUID groupId = memberRepository.findByUserId(user.getId())
                .map(FamilyMember::getGroup)
                .map(group -> group.getId())
                .orElse(null);
        return new Viewer(user.getId(), groupId);
    }

    /** Quem pergunta: o usuário do token e a casa dele, se tiver. */
    record Viewer(UUID userId, UUID groupId) {
        UUID groupOrSentinel() {
            return groupId != null ? groupId : ShoppingTripRepository.NO_GROUP;
        }
    }

    static ShoppingTrip.Status parseStatus(String value) {
        try {
            return ShoppingTrip.Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(INVALID_STATUS_MESSAGE);
        }
    }

    static String requireClientId(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().length() > 64) {
            throw new IllegalArgumentException(INVALID_CLIENT_ID_MESSAGE);
        }
        return raw.trim();
    }

    private static int clamp(Integer requested, int fallback, int max) {
        if (requested == null) return fallback;
        return Math.max(1, Math.min(requested, max));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }
}
