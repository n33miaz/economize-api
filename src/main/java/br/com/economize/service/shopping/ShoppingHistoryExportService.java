package br.com.economize.service.shopping;

import br.com.economize.dto.integration.IntegrationShoppingResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.FamilyMember;
import br.com.economize.model.ShoppingItem;
import br.com.economize.model.ShoppingTrip;
import br.com.economize.model.User;
import br.com.economize.repository.FamilyMemberRepository;
import br.com.economize.repository.IntegrationShoppingTripRepository;
import br.com.economize.repository.ShoppingItemRepository;
import br.com.economize.repository.ShoppingTripRepository;
import br.com.economize.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O histórico de compras para a integração (V44): as idas ao mercado com os
 * itens, página a página, no formato do contrato de integração.
 *
 * <p><b>Só lê, e não muda o carrinho.</b> Mora no pacote do carrinho para
 * reaproveitar as contas dele — {@link ShoppingTripService#total} e
 * {@link ShoppingTripService#spent} — em vez de somar de novo por conta
 * própria: o total que sai daqui é o mesmo que o app mostra.
 *
 * <p><b>Por que não é {@code ShoppingTripService.list}.</b> Aquela leitura é a
 * da tela: no máximo 200 compras, sem como pedir "as seguintes", e sem o nome
 * normalizado dos itens. Migrar um histórico inteiro pede cursor.
 *
 * <p><b>Quem vê o quê</b> é a regra do carrinho, sem afrouxar nem apertar: as
 * compras do dono da chave e as que a casa dele compartilhou. O campo
 * {@code own} diz qual é qual, para quem consome poder ficar só com as dele.
 *
 * <p><b>Item removido não sai.</b> A lápide existe para um aparelho avisar o
 * outro de uma remoção; num histórico exportado ela é só um item que a pessoa
 * não quis.
 */
@Service
@RequiredArgsConstructor
public class ShoppingHistoryExportService {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    static final String INVALID_CURSOR_MESSAGE = "Cursor inválido — use o nextCursor da página anterior";

    private final IntegrationShoppingTripRepository tripRepository;
    private final ShoppingItemRepository itemRepository;
    private final FamilyMemberRepository memberRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public IntegrationShoppingResponses.IntegrationTripPage page(String email, String cursor, Integer limit) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
        UUID userId = user.getId();
        UUID groupId = memberRepository.findByUserId(userId)
                .map(FamilyMember::getGroup)
                .map(group -> group.getId())
                .orElse(ShoppingTripRepository.NO_GROUP);

        int size = limit == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        // Uma a mais que o pedido: é como se sabe que existe página seguinte
        // sem uma segunda consulta de contagem
        PageRequest window = PageRequest.of(0, size + 1);

        List<ShoppingTrip> found;
        if (cursor == null || cursor.isBlank()) {
            found = tripRepository.findFirstPage(userId, groupId, window);
        } else {
            Position after = Position.decode(cursor);
            found = tripRepository.findPageAfter(userId, groupId, after.createdAt(), after.id(), window);
        }

        boolean hasMore = found.size() > size;
        List<ShoppingTrip> trips = hasMore ? found.subList(0, size) : found;
        if (trips.isEmpty()) {
            return new IntegrationShoppingResponses.IntegrationTripPage(List.of(), null, false);
        }

        List<UUID> ids = trips.stream().map(ShoppingTrip::getId).toList();
        Map<UUID, List<ShoppingItem>> itemsByTrip = itemRepository.findAllByTripIdInOrderByCreatedAtAsc(ids)
                .stream()
                .collect(Collectors.groupingBy(ShoppingItem::getTripId, LinkedHashMap::new, Collectors.toList()));

        ShoppingTrip last = trips.get(trips.size() - 1);
        return new IntegrationShoppingResponses.IntegrationTripPage(
                trips.stream()
                        .map(trip -> toTrip(trip, itemsByTrip.getOrDefault(trip.getId(), List.of()), userId))
                        .toList(),
                new Position(last.getCreatedAt(), last.getId()).encode(),
                hasMore);
    }

    private static IntegrationShoppingResponses.IntegrationTrip toTrip(ShoppingTrip trip, List<ShoppingItem> items,
                                                            UUID viewerId) {
        List<IntegrationShoppingResponses.IntegrationTripItem> lines = items.stream()
                .filter(item -> !item.isDeleted())
                .map(ShoppingHistoryExportService::toItem)
                .toList();
        return new IntegrationShoppingResponses.IntegrationTrip(
                trip.getId(), trip.getClientId(), trip.getUserId(), trip.getUserId().equals(viewerId),
                trip.getStoreName(), trip.getStatus().name(),
                instant(trip.getStartedAt()), instant(trip.getClosedAt()),
                cents(trip.getBudget()), cents(trip.getReceiptTotal()),
                // As mesmas contas do carrinho, sobre a lista inteira: elas já
                // deixam de fora a lápide e o item que não foi pego
                cents(ShoppingTripService.total(items)),
                cents(ShoppingTripService.spent(trip, items)),
                trip.getReceiptKey(), trip.getReceiptIssuerCnpj(),
                trip.getReconciledTransactionId(), trip.getNotes(), lines,
                instant(trip.getCreatedAt()), instant(trip.getUpdatedAt()));
    }

    private static IntegrationShoppingResponses.IntegrationTripItem toItem(ShoppingItem item) {
        return new IntegrationShoppingResponses.IntegrationTripItem(
                item.getId(), item.getClientId(), item.getName(), item.getNormalizedName(),
                item.getQuantity(), cents(item.getUnitPrice()),
                item.getUnitPrice() == null ? null : cents(item.getQuantity().multiply(item.getUnitPrice())),
                item.getPromoNote(), item.isChecked(), item.getAddedBy(),
                instant(item.getCreatedAt()), instant(item.getUpdatedAt()));
    }

    /** Reais com casas decimais → centavos inteiros, arredondando ao centavo como o carrinho faz. */
    static Long cents(BigDecimal value) {
        return value == null ? null
                : value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * Onde a página anterior parou. O cursor é opaco para quem consome —
     * base64url de {@code instante|id} — para que a ordenação possa mudar um
     * dia sem quebrar o contrato.
     */
    record Position(OffsetDateTime createdAt, UUID id) {

        String encode() {
            String raw = createdAt.toInstant() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Position decode(String cursor) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
                int separator = raw.indexOf('|');
                if (separator < 0) {
                    throw new IllegalArgumentException(INVALID_CURSOR_MESSAGE);
                }
                return new Position(
                        Instant.parse(raw.substring(0, separator)).atOffset(ZoneOffset.UTC),
                        UUID.fromString(raw.substring(separator + 1)));
            } catch (RuntimeException e) {
                // Base64 quebrado, data ou UUID ilegíveis: tudo é o mesmo erro
                // do cliente, e vira 400 no tratador de IllegalArgumentException
                throw new IllegalArgumentException(INVALID_CURSOR_MESSAGE);
            }
        }
    }
}
