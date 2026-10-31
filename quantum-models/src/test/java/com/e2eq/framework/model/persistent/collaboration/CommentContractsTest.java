package com.e2eq.framework.model.persistent.collaboration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ExternalEntityReference;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CommentContractsTest {

    @Test
    void subjectRequiresExactlyOneLocalOrExternalReference() {
        CommentSubjectReference empty = CommentSubjectReference.builder().build();
        CommentSubjectReference local = CommentSubjectReference.builder()
                .quantumEntity(EntityReference.builder()
                        .entityRefName("DEFECT-42")
                        .entityDisplayName("Defect 42")
                        .build())
                .build();
        CommentSubjectReference external = CommentSubjectReference.builder()
                .externalEntity(ExternalEntityReference.builder()
                        .sourceSystem("issue-tracker")
                        .entityType("Defect")
                        .externalId("defect-42")
                        .build())
                .build();
        CommentSubjectReference ambiguous = CommentSubjectReference.builder()
                .quantumEntity(local.getQuantumEntity())
                .externalEntity(external.getExternalEntity())
                .build();

        assertFalse(empty.isTargetValid());
        assertTrue(local.isTargetValid());
        assertTrue(external.isTargetValid());
        assertFalse(ambiguous.isTargetValid());
    }

    @Test
    void mediaReferenceCannotEmbedPayloadBytesOrSignedUrls() {
        Set<String> fieldNames = Arrays.stream(MediaReference.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertFalse(fieldNames.contains("bytes"));
        assertFalse(fieldNames.contains("data"));
        assertFalse(fieldNames.contains("base64"));
        assertFalse(fieldNames.contains("url"));
        assertFalse(fieldNames.contains("signedUrl"));
    }
}
