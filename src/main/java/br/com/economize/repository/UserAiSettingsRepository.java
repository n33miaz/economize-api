package br.com.economize.repository;

import br.com.economize.model.UserAiSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserAiSettingsRepository extends JpaRepository<UserAiSettings, UUID> {

    /**
     * A ÚNICA porta de leitura. Sempre pelo dono: não existe busca por id solto,
     * então nenhuma rota consegue alcançar a configuração de outra conta — a
     * fronteira é estrutural, não uma checagem que alguém pode esquecer.
     *
     * <p>O acompanhamento de rotação de chave-mestra NÃO mora aqui: ele é um
     * {@code SELECT count(*) ... GROUP BY master_key_id} rodado direto no banco
     * pelo operador (ver o comentário da coluna na V17). Um método de repositório
     * sem chamador nenhum daria a impressão de que existe uma operação pronta na
     * API quando não existe.
     */
    Optional<UserAiSettings> findByUserId(UUID userId);
}
