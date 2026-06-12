package com.forge.contracttesting.service;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.Personalization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final SendGrid sendGrid;

    @Value("${mail.from}")
    private String fromEmail;

    /**
     * Send a SendGrid dynamic-template email.
     *
     * @param toEmail    recipient address
     * @param data       key/value pairs merged into the template
     * @param templateId SendGrid template id (d-xxxx...)
     */
    public EmailDeliveryResult sendDynamicEmail(String toEmail, Map<String, Object> data, String templateId) {
        try {
            Mail mail = new Mail();
            mail.setFrom(new Email(fromEmail));
            mail.setTemplateId(templateId);

            Personalization personalization = new Personalization();
            personalization.addTo(new Email(toEmail));
            data.forEach(personalization::addDynamicTemplateData);
            mail.addPersonalization(personalization);

            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);
            boolean success = response.getStatusCode() >= 200 && response.getStatusCode() < 300;

            if (success) {
                log.info("Approval email sent to {} via template {}. Status: {}",
                        toEmail, templateId, response.getStatusCode());
            } else {
                log.warn("SendGrid returned {} for template {} to {}: {}",
                        response.getStatusCode(), templateId, toEmail, response.getBody());
            }

            return new EmailDeliveryResult(success, response.getStatusCode(), response.getBody(), null);

        } catch (IOException e) {
            log.error("Failed to send approval email to {}: {}", toEmail, e.getMessage());
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
