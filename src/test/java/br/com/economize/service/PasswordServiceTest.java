package br.com.economize.service;

import br.com.economize.model.PasswordResetToken;
import br.com.economize.model.User;
import br.com.economize.repository.PasswordResetTokenRepository;
import br.com.economize.repository.UserRepository;
import br.com.economize.service.mail.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordServiceTest {

    private static final String EMAIL = "ana@economize.dev";
    private static final String RESET_URL_BASE = "https://app.test/reset-password";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetTokenRepository tokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailSender emailSender;

    private PasswordService service;

    private final User user = User.builder()
            .id(UUID.randomUUID()).name("Ana").email(EMAIL).password("$2a$10$hashAtual").build();

    @BeforeEach
    void setUp() {
        service = new PasswordService(userRepository, tokenRepository, passwordEncoder, emailSender, RESET_URL_BASE);
    }

    @Test
    @DisplayName("forgot-password: gera token aleatório, persiste só o SHA-256 e envia o link por e-mail")
    void forgotPasswordGeneratesTokenAndPersistsOnlyHash() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> inv.getArgument(0));

        service.forgotPassword(EMAIL);

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendPasswordResetEmail(eq(EMAIL), linkCaptor.capture());

        PasswordResetToken saved = tokenCaptor.getValue();
        String link = linkCaptor.getValue();
        assertThat(link).startsWith(RESET_URL_BASE + "?token=");
        String rawToken = link.substring((RESET_URL_BASE + "?token=").length());

        // 32 bytes em base64url sem padding = 43 caracteres
        assertThat(rawToken).hasSize(43);
        assertThat(saved.getTokenHash()).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(rawToken);
        assertThat(saved.getTokenHash()).isEqualTo(PasswordService.sha256Hex(rawToken));
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getUsedAt()).isNull();
        assertThat(saved.getExpiresAt()).isBetween(
                OffsetDateTime.now().plusMinutes(29), OffsetDateTime.now().plusMinutes(31));
    }

    @Test
    @DisplayName("forgot-password: invalida tokens anteriores não usados do mesmo usuário")
    void forgotPasswordInvalidatesPreviousUnusedTokens() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> inv.getArgument(0));

        service.forgotPassword(EMAIL);

        verify(tokenRepository).deleteByUserIdAndUsedAtIsNull(user.getId());
    }

    @Test
    @DisplayName("forgot-password: e-mail inexistente não gera token, não envia e-mail e não lança erro")
    void forgotPasswordIsNeutralForUnknownEmail() {
        when(userRepository.findByEmail("nao-existe@economize.dev")).thenReturn(Optional.empty());

        service.forgotPassword("nao-existe@economize.dev");

        verify(tokenRepository, never()).save(any());
        verify(tokenRepository, never()).deleteByUserIdAndUsedAtIsNull(any());
        verify(emailSender, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("reset-password: token válido troca a senha com BCrypt e marca used_at")
    void resetPasswordUpdatesPasswordAndMarksTokenUsed() {
        String rawToken = "token-valido";
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .id(UUID.randomUUID()).user(user)
                .tokenHash(PasswordService.sha256Hex(rawToken))
                .expiresAt(OffsetDateTime.now().plusMinutes(10))
                .build();
        when(tokenRepository.findByTokenHash(PasswordService.sha256Hex(rawToken)))
                .thenReturn(Optional.of(resetToken));
        when(passwordEncoder.encode("NovaSenha123")).thenReturn("$2a$10$novoHash");

        service.resetPassword(rawToken, "NovaSenha123");

        assertThat(user.getPassword()).isEqualTo("$2a$10$novoHash");
        assertThat(resetToken.getUsedAt()).isNotNull();
        verify(userRepository).save(user);
        verify(tokenRepository).save(resetToken);
    }

    @Test
    @DisplayName("reset-password: token expirado é rejeitado com mensagem neutra")
    void resetPasswordRejectsExpiredToken() {
        String rawToken = "token-expirado";
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .id(UUID.randomUUID()).user(user)
                .tokenHash(PasswordService.sha256Hex(rawToken))
                .expiresAt(OffsetDateTime.now().minusMinutes(1))
                .build();
        when(tokenRepository.findByTokenHash(PasswordService.sha256Hex(rawToken)))
                .thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> service.resetPassword(rawToken, "NovaSenha123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Token inválido ou expirado");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("reset-password: token já usado não pode ser reutilizado")
    void resetPasswordRejectsAlreadyUsedToken() {
        String rawToken = "token-usado";
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .id(UUID.randomUUID()).user(user)
                .tokenHash(PasswordService.sha256Hex(rawToken))
                .expiresAt(OffsetDateTime.now().plusMinutes(10))
                .usedAt(OffsetDateTime.now().minusMinutes(5))
                .build();
        when(tokenRepository.findByTokenHash(PasswordService.sha256Hex(rawToken)))
                .thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> service.resetPassword(rawToken, "NovaSenha123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Token inválido ou expirado");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("reset-password: token desconhecido é rejeitado com a mesma mensagem neutra")
    void resetPasswordRejectsUnknownToken() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("nao-existe", "NovaSenha123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Token inválido ou expirado");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("change-password: senha atual incorreta é rejeitada sem alterar nada")
    void changePasswordRejectsWrongCurrentPassword() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("errada", user.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(EMAIL, "errada", "NovaSenha123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Senha atual incorreta");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("change-password: senha atual correta troca a senha com BCrypt")
    void changePasswordUpdatesPasswordWhenCurrentMatches() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("SenhaAtual1", "$2a$10$hashAtual")).thenReturn(true);
        when(passwordEncoder.encode("NovaSenha123")).thenReturn("$2a$10$novoHash");

        service.changePassword(EMAIL, "SenhaAtual1", "NovaSenha123");

        assertThat(user.getPassword()).isEqualTo("$2a$10$novoHash");
        verify(userRepository).save(user);
    }
}
