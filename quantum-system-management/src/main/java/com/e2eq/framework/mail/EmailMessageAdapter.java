package com.e2eq.framework.mail;

import com.e2eq.framework.model.messaging.MessageChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Email as a {@link MessageChannelAdapter}: resolves the template by channel and locale,
 * renders it with Qute and hands it to the application's {@link EmailDeliveryService}
 * (SMTP, Postmark or another sender), which is used as it is.
 */
@ApplicationScoped
public class EmailMessageAdapter implements MessageChannelAdapter {

    private final EmailTemplateResolver templateResolver;
    private final EmailTemplateRenderService renderService;
    private final Instance<EmailDeliveryService> senders;

    @Inject
    public EmailMessageAdapter(
        EmailTemplateResolver templateResolver,
        EmailTemplateRenderService renderService,
        Instance<EmailDeliveryService> senders
    ) {
        this.templateResolver = templateResolver;
        this.renderService = renderService;
        this.senders = senders;
    }

    @Override
    public MessageChannel channel() {
        return MessageChannel.EMAIL;
    }

    @Override
    public String providerId() {
        return senders.isResolvable() ? senders.get().providerId() : "email";
    }

    @Override
    public DeliveryReceipt send(MessageRequest request) {
        if (request.recipient().channel() != MessageChannel.EMAIL) {
            throw new MessageDeliveryException(MessageDeliveryException.Reason.CHANNEL_NOT_SUPPORTED,
                "the email adapter cannot send to channel " + request.recipient().channel());
        }
        if (!senders.isResolvable()) {
            throw new MessageDeliveryException(MessageDeliveryException.Reason.DELIVERY_UNAVAILABLE,
                senders.isAmbiguous()
                    ? "several EmailDeliveryService beans are registered"
                    : "no EmailDeliveryService bean is registered");
        }
        EmailTemplateDefinition template = templateResolver
            .resolve(request.realm(), request.templateKey(), MessageChannel.EMAIL, request.locale())
            .orElseThrow(() -> new MessageDeliveryException(MessageDeliveryException.Reason.TEMPLATE_NOT_FOUND,
                "no email template " + request.templateKey()));
        if (template.providerRendered()) {
            throw new MessageDeliveryException(MessageDeliveryException.Reason.PROVIDER_TEMPLATE_UNSUPPORTED,
                "email template " + request.templateKey() + " names a provider template, which the email adapter does not send");
        }
        List<String> missing = template.missingParameters(request.parameters());
        if (!missing.isEmpty()) {
            throw new MessageDeliveryException(MessageDeliveryException.Reason.TEMPLATE_PARAMETER_MISSING,
                "template " + template.templateKey() + " needs parameters " + missing);
        }

        RenderedTemplate rendered = renderService.render(template, request.parameters());
        RenderedEmail email = RenderedEmail.builder()
            .from(request.sender())
            .to(List.of(request.recipient().address()))
            .subject(rendered.subject().trim())
            .htmlBody(rendered.htmlBody())
            .textBody(rendered.textBody())
            .build();
        DeliveryReceipt receipt = senders.get().deliverWithReceipt(email);
        if (receipt == null) {
            throw new MessageDeliveryException(MessageDeliveryException.Reason.DELIVERY_UNAVAILABLE,
                "the email sender returned no receipt");
        }
        return new DeliveryReceipt(receipt.providerId(), MessageChannel.EMAIL, receipt.providerMessageId(),
            receipt.status(), receipt.acceptedAt(), request.idempotencyKey());
    }

}
