package com.e2eq.framework.rest.requests;

import com.e2eq.framework.model.security.DomainContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Set;

@Data
@EqualsAndHashCode
@SuperBuilder
@ToString
@NoArgsConstructor
public class CreateUserRequest {
   @NotNull
   @NonNull
   String userId;

   // can be optional
   String password;

   String displayName;

   String firstName;
   String lastName;


   @Pattern(regexp = "^\\+?[0-9. ()-]{7,25}$", message = "Invalid phone number")
   String phoneNumber;

   String defaultLanguage;
   String defaultTimezone;
   String defaultCurrency;

   Boolean forceChangePassword;

   /**
    * The user's email address where we will send the user information  / confirmation
    *
    * @return The email address.
    * @since 1.0.0
    * @see <a href="https://tools.ietf.org/html/rfc5322">RFC 5322</a> for more information on the email format.
    * @see <a href="https://en.wikipedia.org/wiki/Email_address">Email address</a> for more information on what constitutes an email address.
    * @see <a href="https://www.iana.org/domains/example">Example email domains</a> for some common email domains.
    * @see <a href="https://www.iana.org/domains/example/example-domains">Example domains</a> for more information on example email domains.
    */
   @NotNull
   @NonNull
   @Email
   String email;

   Set<String> roles;
   @NotNull
   @NonNull
   @Valid
   DomainContext domainContext;

   /**
    * Application boundary for the created credential: {@code "*"} authorizes any
    * application; otherwise a regular expression matched against application ids.
    * Login mints application-scoped tokens and rejects credentials that carry no
    * boundary and no per-realm application grant, so when this field is omitted the
    * provider applies its documented default ({@code "*"} for the custom provider —
    * the pre-application-scoping behavior) to keep the new user loginable.
    */
   String applicationRegEx;
}
