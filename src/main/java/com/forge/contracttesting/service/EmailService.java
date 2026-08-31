package com.forge.contracttesting.service;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.Personalization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final SecretsService secretsService;

    @Value("${mail.from}")
    private String fromEmail;

    /**
     * Send a fully self-contained HTML email — no SendGrid dashboard template
     * required. The caller builds the complete HTML body itself.
     *
     * @param toEmail  recipient address
     * @param subject  email subject line
     * @param htmlBody complete HTML document/body to send as the message content
     */
    public EmailDeliveryResult sendHtmlEmail(String toEmail, String subject, String htmlBody) {
        try {
            SendGrid sendGrid = new SendGrid(secretsService.requireValue("SENDGRID_API_KEY"));

            Mail mail = new Mail();
            mail.setFrom(new Email(fromEmail));
            mail.setSubject(subject);
            mail.addContent(new Content("text/html", htmlBody));

            Personalization personalization = new Personalization();
            personalization.addTo(new Email(toEmail));
            mail.addPersonalization(personalization);

            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);
            boolean success = response.getStatusCode() >= 200 && response.getStatusCode() < 300;

            if (success) {
                log.info("Email '{}' sent to {}. Status: {}", subject, toEmail, response.getStatusCode());
            } else {
                log.warn("SendGrid returned {} for email '{}' to {}: {}",
                        response.getStatusCode(), subject, toEmail, response.getBody());
            }

            return new EmailDeliveryResult(success, response.getStatusCode(), response.getBody(), null);

        } catch (IOException e) {
            log.error("Failed to send email to {}: {}", toEmail, e.getMessage());
            return new EmailDeliveryResult(false, null, null, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("Cannot send email to {}: {}", toEmail, e.getMessage());
            return new EmailDeliveryResult(false, null, null, e.getMessage());
        }
    }

    public record EmailDeliveryResult(
            boolean success,
            Integer statusCode,
            String responseBody,
            String errorMessage
    ) {}
}
