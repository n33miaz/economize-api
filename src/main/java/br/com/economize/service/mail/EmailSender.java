package br.com.economize.service.mail;

/**
 * Abstração do envio de e-mails transacionais. A implementação ativa depende
 * de {@code economize.mail.enabled}: desligado usa {@link LogEmailSender}
 * (padrão, não envia nada); ligado usa {@link SmtpEmailSender}.
 */
public interface EmailSender {

    void sendPasswordResetEmail(String to, String resetLink);
}
