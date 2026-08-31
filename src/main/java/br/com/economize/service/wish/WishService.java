package br.com.economize.service.wish;

import br.com.economize.dto.wish.WishRequests;
import br.com.economize.dto.wish.WishResponses;
import br.com.economize.exception.ResourceNotFoundException;
import br.com.economize.model.User;
import br.com.economize.model.Wish;
import br.com.economize.repository.CategoryRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.repository.WishRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * O cadastro de desejos (EC-140) — a parte mundana. A conta interessante mora
 * no {@link WishProjectionService}.
 *
 * <p>A listagem calcula o retrato financeiro UMA vez e projeta todos os desejos
 * contra ele: dez desejos custam as mesmas seis consultas de ciclo que um.
 */
@Service
@RequiredArgsConstructor
public class WishService {

    /**
     * Teto por usuário. Não é limite de negócio, é rede contra script: cada
     * desejo é barato, mas a listagem varre todos e o app renderiza todos.
     */
    private static final int MAX_WISHES = 100;

    private final WishRepository wishRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final WishProjectionService projectionService;

    @Transactional(readOnly = true)
    public WishResponses.WishList list(String email) {
        User user = requireUser(email);
        WishBaseline baseline = projectionService.baselineFor(user.getId());

        List<WishResponses.WishItem> items = new ArrayList<>();
        for (Wish wish : wishRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId())) {
            items.add(WishResponses.WishItem.from(wish, projectionService.project(wish, baseline)));
        }
        return new WishResponses.WishList(WishResponses.Baseline.from(baseline), items);
    }

    @Transactional(readOnly = true)
    public WishResponses.WishItem get(String email, UUID id) {
        User user = requireUser(email);
        Wish wish = requireWish(user.getId(), id);
        WishBaseline baseline = projectionService.baselineFor(user.getId());
        return WishResponses.WishItem.from(wish, projectionService.project(wish, baseline));
    }

    @Transactional
    public WishResponses.WishItem create(String email, WishRequests.CreateWish request) {
        User user = requireUser(email);
        if (wishRepository.countByUserId(user.getId()) >= MAX_WISHES) {
            throw new IllegalArgumentException(
                    "Limite de " + MAX_WISHES + " desejos atingido — arquive algum antes de criar outro");
        }

        BigDecimal saved = request.savedAmount() != null ? request.savedAmount() : BigDecimal.ZERO;
        if (saved.compareTo(request.targetAmount()) > 0) {
            throw new IllegalArgumentException("Valor já guardado não pode ser maior que o valor do desejo");
        }
        validateCategory(request.categoryId());

        Wish wish = Wish.builder()
                .user(user)
                .name(request.name().trim())
                .targetAmount(request.targetAmount())
                .savedAmount(saved)
                .categoryId(request.categoryId())
                .status(Wish.Status.WISH)
                .targetDate(request.targetDate())
                .note(trimToNull(request.note()))
                .build();

        Wish stored = wishRepository.save(wish);
        WishBaseline baseline = projectionService.baselineFor(user.getId());
        return WishResponses.WishItem.from(stored, projectionService.project(stored, baseline));
    }

    @Transactional
    public WishResponses.WishItem update(String email, UUID id, WishRequests.UpdateWish request) {
        User user = requireUser(email);
        Wish wish = requireWish(user.getId(), id);

        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("Nome do desejo não pode ser vazio");
            wish.setName(name);
        }
        if (request.targetAmount() != null) wish.setTargetAmount(request.targetAmount());
        if (request.savedAmount() != null) wish.setSavedAmount(request.savedAmount());
        if (request.categoryId() != null) {
            validateCategory(request.categoryId());
            wish.setCategoryId(request.categoryId());
        }
        if (request.targetDate() != null) wish.setTargetDate(request.targetDate());
        if (request.note() != null) wish.setNote(trimToNull(request.note()));
        if (request.status() != null) wish.setStatus(parseStatus(request.status()));

        // A checagem roda DEPOIS de aplicar tudo: um PATCH que sobe o alvo e o
        // guardado ao mesmo tempo seria rejeitado se comparado campo a campo
        if (wish.getSavedAmount().compareTo(wish.getTargetAmount()) > 0) {
            throw new IllegalArgumentException("Valor já guardado não pode ser maior que o valor do desejo");
        }

        Wish stored = wishRepository.save(wish);
        WishBaseline baseline = projectionService.baselineFor(user.getId());
        return WishResponses.WishItem.from(stored, projectionService.project(stored, baseline));
    }

    /**
     * O desejo virou compra. Marcar o desejo como comprado sem mexer no
     * {@code savedAmount} é proposital: o histórico deve continuar contando
     * quanto a pessoa tinha juntado quando comprou.
     */
    @Transactional
    public WishResponses.WishItem purchase(String email, UUID id, WishRequests.PurchaseWish request) {
        User user = requireUser(email);
        Wish wish = requireWish(user.getId(), id);

        LocalDate when = request.purchasedAt() != null
                ? request.purchasedAt() : LocalDate.now(ZoneOffset.UTC);
        if (when.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException("Data da compra não pode estar no futuro");
        }
        wish.setStatus(Wish.Status.PURCHASED);
        wish.setPurchasedAt(when);
        wish.setPurchaseTransactionId(request.transactionId());

        Wish stored = wishRepository.save(wish);
        WishBaseline baseline = projectionService.baselineFor(user.getId());
        return WishResponses.WishItem.from(stored, projectionService.project(stored, baseline));
    }

    @Transactional
    public void delete(String email, UUID id) {
        User user = requireUser(email);
        wishRepository.delete(requireWish(user.getId(), id));
    }

    private Wish requireWish(UUID userId, UUID id) {
        return wishRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Desejo não encontrado"));
    }

    private void validateCategory(UUID categoryId) {
        if (categoryId == null) return;
        if (!categoryRepository.existsById(categoryId)) {
            throw new IllegalArgumentException("Categoria não encontrada");
        }
    }

    private Wish.Status parseStatus(String raw) {
        try {
            return Wish.Status.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Status inválido: use WISH, GOAL, PURCHASED ou ARCHIVED");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }
}
