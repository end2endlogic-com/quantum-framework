package com.e2eq.framework.model.persistent.email;

import com.e2eq.framework.model.messaging.MessageChannel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * One channel and locale of a template key, stored inside its {@link EmailTemplate}.
 *
 * <p>A template key stays one document per purpose (for example {@code auth.magic-link}).
 * The document's own subject/html/text fields remain the email template in the default
 * locale; a variant adds another channel and/or locale. A {@code null} locale is the
 * channel's default-locale variant.
 *
 * <p>Templates rendered by Helixor use {@code subjectTemplate}/{@code htmlTemplate}/
 * {@code textTemplate}. Templates pre-approved by a provider set {@code providerTemplateId}
 * (and, where the provider needs one, {@code senderSignature}); nothing is rendered locally
 * and only the {@code parameterNames} are sent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@RegisterForReflection
public class MessageTemplateVariant {
    private MessageChannel channel;
    /** BCP 47 language tag, for example {@code pt-BR}; {@code null} for the default locale. */
    private String locale;

    @Builder.Default
    private boolean active = true;

    private String subjectTemplate;
    private String htmlTemplate;
    private String textTemplate;

    private String providerTemplateId;
    private String senderSignature;

    /** Parameters the template requires; a send missing any of them is refused. */
    @Builder.Default
    private List<String> parameterNames = new ArrayList<>();
}
