package com.e2eq.framework.util;

import com.e2eq.framework.annotations.ValidMailingAddress;
import com.e2eq.framework.model.persistent.base.MailingAddress;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class MailingAddressValidator implements ConstraintValidator<ValidMailingAddress, MailingAddress> {

    private ValidMailingAddress annotation;

    private static final Pattern US_STATE_PATTERN = Pattern.compile("[A-Z]{2}");

    private static final Pattern ZIP4_PATTERN = Pattern.compile("\\d{4}");

    private static final Pattern ZIP5_PATTERN = Pattern.compile("\\d{5}");

    @Override
    public void initialize(ValidMailingAddress validAddress) {
        this.annotation = validAddress;
    }

    @Override
    public boolean isValid(MailingAddress address, ConstraintValidatorContext constraintValidatorContext) {

        boolean rc = true;
        String violationMessage;
        Set<String> violationMessages = new HashSet<>();

        if (address == null) {
            rc = true;
        } else {
            // first check that the address has a address name
            // check if the address has a address line 1 that is none null none empty
            // check if the address has a city that is none null and non empty
            // check if the address either has a state or a stateTwoLetterCode but not both
            // if there is a stateLetterTwoCode ensure its only two characters
            // check if there is a countryTwoLetterCode or a countyName is provided
            // if neither is provided then ensure zip, or zip5 is provided.
            // if neither is provided, or countryTwoLettercode is provided and its value is US or
            // country is provided and its value is USA or united states, or united states of america then
            // either zip or zip5 must be non-null.  If zip5 is provided it must be 5 numbers.
            // if zip is provided it must be 5 numbers.
            // if zip5 is provided it must be 5 numbers.

            if (StringUtils.isBlank(address.getAddressLine1())) {
                violationMessage = "Address Line 1 is mandatory, but is not provided in the mailing address";
                constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                       .addPropertyNode("addressLine1").addConstraintViolation();
                violationMessages.add(violationMessage);
                rc = false;
            }
            if (StringUtils.isBlank(address.getCity())) {
                violationMessage = "City is mandatory, but is not provided in the mailing address";
                constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                       .addPropertyNode("city").addConstraintViolation();
                violationMessages.add(violationMessage);
                rc = false;
            }

            if (address.getCountryTwoLetterCode() == null && address.getCountry() == null) {
                violationMessage = "Country is mandatory either countryTwoLettercode or country must be provided";
                constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                        .addPropertyNode("country").addConstraintViolation();
                violationMessages.add(violationMessage);
                rc = false;
            } else
            if (address.getCountryTwoLetterCode()!= null &&! US_STATE_PATTERN.matcher(address.getCountryTwoLetterCode()).matches()) {
                violationMessage = "Country Two Letter Code is not valid";
                constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                        .addPropertyNode("countryTwoLetterCode").addConstraintViolation();
                violationMessages.add(violationMessage);
                rc = false;
            }  else
           if ((address.getCountry() != null && !address.getCountry().isBlank()) && (address.getCountryTwoLetterCode() != null || !address.getCountryTwoLetterCode().isBlank())) {
               violationMessage = "Can't provide both country and countryTwoLetterCode";
               constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                       .addPropertyNode("countryTwoLetterCode").addConstraintViolation();
               violationMessages.add(violationMessage);
               rc = false;
           }
           else
            if (((
                    (address.getCountry() == null || address.getCountry().isBlank()) &&
                    (address.getCountryTwoLetterCode() == null || address.getCountryTwoLetterCode().isBlank())
            ) ||
                    (  address.getCountry() != null && (
                    "united states".equalsIgnoreCase(address.getCountry()) ||
                    "united states of america".equalsIgnoreCase(address.getCountry()) ||
                    "us".equalsIgnoreCase(address.getCountry()) ||
                    "usa".equalsIgnoreCase(address.getCountry() )))  || (
                       address.getCountryTwoLetterCode()!= null &&
                           "US".equals(address.getCountryTwoLetterCode())
                    ))) {
                        if ((address.getState() ==null || address.getState().isBlank()) && (
                                address.getStateTwoLetterCode() == null || address.getStateTwoLetterCode().isBlank()
                                )) {
                            violationMessage = "State is mandatory, but is not provided in the mailing address";
                            constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                                    .addPropertyNode("state").addConstraintViolation();
                            violationMessages.add(violationMessage);
                            rc = false;
                        }

                    if ( (address.getZip() != null && !address.getZip().isBlank()) &&
                            (address.getZip5() != null && !address.getZip5().isBlank())) {
                        violationMessage = "Can't provide both zip and zip5";
                        constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                                .addPropertyNode("zip5").addConstraintViolation();
                        violationMessages.add(violationMessage);
                        rc = false;
                    } else
                    if ((address.getZip() == null || address.getZip().isBlank()) &&
                            ( address.getZip5() == null || address.getZip5().isBlank()))  {
                       violationMessage = "Zip is mandatory, but is not provided in the mailing address or is not valid";
                        constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                                .addPropertyNode("zip").addConstraintViolation();
                        rc = false;
                    }

                    if (address.getZip5() != null && !ZIP5_PATTERN.matcher(address.getZip5()).matches()) {
                        violationMessage = "Zip5 is not valid";
                        constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                                .addPropertyNode("zip5").addConstraintViolation();
                        rc = false;
                    }
            } else  {
                if (address.getPostalCode() == null || address.getPostalCode().isBlank()) {
                    violationMessage = "Postal Code is mandatory for non domestic addresses, but is not provided in the mailing address";
                    constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                            .addPropertyNode("postalCode").addConstraintViolation();
                    violationMessages.add(violationMessage);
                    rc = false;
                }
            }

            if ((address.getState() != null && !address.getState().isBlank() ) &&
                    (address.getStateTwoLetterCode() != null && !address.getStateTwoLetterCode().isBlank())) {
                violationMessage= "Can't provide both state and stateTwoLetterCode";
                constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                        .addPropertyNode("stateTwoLetterCode").addConstraintViolation();
                violationMessages.add(violationMessage);
                rc = false;
            }

        }

        if (!rc) {
            address.setValidationMessage(String.valueOf(violationMessages));
        }

        return rc;
    }
}
