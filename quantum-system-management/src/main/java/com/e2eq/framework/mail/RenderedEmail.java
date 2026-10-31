package com.e2eq.framework.mail;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenderedEmail {

    private String from;

    @Builder.Default
    private List<String> to = new ArrayList<>();

    @Builder.Default
    private List<String> cc = new ArrayList<>();

    @Builder.Default
    private List<String> bcc = new ArrayList<>();

    private String replyTo;
    private String messageStream;
    private String subject;
    private String htmlBody;
    private String textBody;
}
