package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.annotations.ValidMailingAddress;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;


/**
 * Embeddable class for Domestic and International mailing addresses
 */
@Entity
@RegisterForReflection
@ValidMailingAddress
@EqualsAndHashCode
@ToString
@NoArgsConstructor
@SuperBuilder
@Data
public  class MailingAddress {
    protected String addressName;
    protected String addressLine1;
    protected String addressLine2;
    protected String addressLine3;
    protected String city;
    // Handled in the ValidMailingAddressValidator
    protected String stateTwoLetterCode;
    protected String state;
    protected String zip;
    // Handled in the ValidMailingAddressValidator
    //@Size(min=4, max=4)
    protected String zip4;
    // Handled in the ValidMailingAddressValidator
    //@Size(min=5, max=5)
    protected String zip5;
    protected String postalCode;
    // Handled in the ValidMailingAddressValidator
    //@Size(min=2, max=2)
    protected String countryTwoLetterCode;
    protected String country;
    @Builder.Default
    protected boolean validated=false;
    @Builder.Default
    protected Set<AddressRole> addressRoles = new HashSet<>();
    protected Date lastValidationAttempt;
    protected String validationMessage;
    protected Coordinate coordinates;
}
