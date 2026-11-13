package br.com.economize.repository;

import br.com.economize.model.PluggyItem;
import br.com.economize.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A consulta que decide quem a sincronização automática visita, rodando de
 * verdade: só entra quem tem ao menos uma conexão ainda legível.
 */
@DataJpaTest
@DisplayName("PluggyItemRepository.findUsersWithReadableItems (auto-sync)")
class PluggyItemAutoSyncQueryTest {

    @Autowired
    private PluggyItemRepository repository;

    @Autowired
    private UserRepository userRepository;

    private User user(String email) {
        return userRepository.save(User.builder()
                .name(email)
                .email(email)
                .password("nao-importa")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }

    private void item(User owner, String itemId, String status) {
        repository.save(PluggyItem.builder().user(owner).itemId(itemId).status(status).build());
    }

    @Test
    @DisplayName("inclui conexão viva ou nunca conferida; exclui sem item e só itens mortos; sem repetir usuário")
    void soQuemTemConexaoLegivel() {
        User vivo = user("vivo@economize.test");
        item(vivo, "i1", "UPDATED");
        item(vivo, "i2", "LOGIN_ERROR");
        User nuncaConferido = user("novo@economize.test");
        item(nuncaConferido, "i3", null);
        User misto = user("misto@economize.test");
        item(misto, "i4", "NOT_FOUND");
        item(misto, "i5", "OUTDATED");
        User morto = user("morto@economize.test");
        item(morto, "i6", "NOT_FOUND");
        item(morto, "i7", "needs_user_action");
        user("semitem@economize.test");

        List<User> users = repository.findUsersWithReadableItems();

        assertThat(users).extracting(User::getEmail)
                .containsExactlyInAnyOrder("vivo@economize.test", "novo@economize.test", "misto@economize.test");
    }
}
