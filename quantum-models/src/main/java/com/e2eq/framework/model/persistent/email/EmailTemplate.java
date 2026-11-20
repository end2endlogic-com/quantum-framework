package com.e2eq.framework.model.persistent.email;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Realm-scoped template override rendered by Qute.
 *
 * <p>One document per template key. Its subject/html/text fields are the email template in
 * the default locale (unchanged since before multi-channel delivery). {@link #variants} add
 * other channels and locales of the same key; see {@link MessageTemplateVariant}.
 */
@Entity(value = "email_templates", useDiscriminator = false)
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@Indexes({
    @Index(fields = {
        @Field("templateKey")
    }, options = @IndexOptions(unique = true, name = "uidx_email_template_key"))
})
public class EmailTemplate extends BaseModel {

    public enum SourceType {
        REALM_OVERRIDE,
        CLASSPATH_DEFAULT
    }

    private String templateKey;
    private String description;

    @Builder.Default
    private boolean active = true;

    private String subjectTemplate;
    private String htmlTemplate;
    private String textTemplate;

    @Builder.Default
    private SourceType sourceType = SourceType.REALM_OVERRIDE;

    private String classpathTemplateBaseName;
    private String functionalArea;
    private String sampleContextJson;
    private String schemaVersion;

    /** Parameters the default email template requires; a send missing any is refused. */
    @Builder.Default
    private List<String> parameterNames = new ArrayList<>();

    /** Other channels and locales of this key. Absent on documents written before them. */
    @Builder.Default
    private List<MessageTemplateVariant> variants = new ArrayList<>();

    @Override
    public String bmFunctionalArea() {
        return "SYSTEM";
    }

    @Override
    public String bmFunctionalDomain() {
        return "EMAIL_TEMPLATE";
    }
}
