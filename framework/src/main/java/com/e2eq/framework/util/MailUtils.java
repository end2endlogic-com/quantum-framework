package com.e2eq.framework.util;


import com.e2eq.framework.config.PostMarkConfig;
import com.e2eq.framework.exceptions.E2eqException;
import com.postmarkapp.postmark.Postmark;
import com.postmarkapp.postmark.client.ApiClient;
import com.postmarkapp.postmark.client.data.model.message.Message;
import com.postmarkapp.postmark.client.data.model.message.MessageResponse;
import com.postmarkapp.postmark.client.exception.PostmarkException;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Email;

import java.io.IOException;

@ApplicationScoped
public class MailUtils {

    @Inject
    PostMarkConfig config;

    @Inject
    Template contactUsNotification;



   /** public String sendContactUsNotification(@NotNull String subject, @Valid ContactUsRequest contactUsRequest) throws B2BiException {
        String htmlBody = contactUsNotification.data("contactUs", contactUsRequest).render();
        return this.sendMail(config.defaultToEmailAddress(), config.defaultFromEmailAddress(), subject, htmlBody);
    }
   **/

    public String sendMail(@Email String to, @Email  String from, String subject, String htmlBody) throws E2eqException {
        ApiClient client = Postmark.getApiClient(config.apiKey());
        Message message = new Message(from,
                to,
                subject,
                htmlBody);
        message.setMessageStream("contactus");

        try {
            MessageResponse response = client.deliverMessage(message);
            return response.getMessageId();
        } catch (PostmarkException e) {
            throw new E2eqException(e);
        } catch (IOException e) {
            throw new E2eqException(e);
        }
    }
}
