package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.annotations.ValidMailingAddress;
import com.e2eq.framework.model.general.AddressRole;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;


/**
 * Embeddable class for Domestic and International mailing addresses
 */
@Entity
@RegisterForReflection
@ValidMailingAddress(message = "Invalid address")
@EqualsAndHashCode
@ToString
public @Data class MailingAddress {
    protected String addressName;
    protected String addressLine1;
    protected String addressLine2;
    protected String addressLine3;
    protected String city;
    @Size(min=2, max=2)
    protected String stateTwoLetterCode;
    protected String state;
    protected String zip;
    @Size(min=4, max=4)
    protected String zip4;
    @Size(min=5, max=5)
    protected String zip5;
    protected String postalCode;
    @Size(min=2, max=2)
    protected String countryTwoLetterCode;
    protected String country;
    protected boolean validated=false;
    protected Set<AddressRole> addressRoles = new HashSet<>();
    protected Date lastValidationAttempt;
    protected String validationMessage;
    protected Coordinate coordinates;
}
