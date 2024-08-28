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
            // Look at
            if (StringUtils.isBlank(address.getCountryTwoLetterCode())) {

                violationMessage = "Country is mandatory, but is not provided in the mailing address";
                constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage).addPropertyNode
                        ("country").addConstraintViolation();
                violationMessages.add(violationMessage);

                rc = false;

            } else {
                if ("US".equals(address.getCountryTwoLetterCode()) || "USA".equals(address.getCountryTwoLetterCode())) {
                    if (StringUtils.isBlank(address.getStateTwoLetterCode())) {
                        violationMessage = "Country is US, however state Code is not the provided state: " + address
                                .getStateTwoLetterCode() + " Note stateName may be filled, or be null in but state code is " +
                                "required, stateName: " + address.getStateTwoLetterCode();
                        constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                                .addPropertyNode("state").addConstraintViolation();
                        violationMessages.add(violationMessage);
                        rc = false;
                    } else if (!US_STATE_PATTERN.matcher(address.getStateTwoLetterCode()).matches()) {
                        violationMessage = "Country is US, however state Code does not comply with the pattern for 2 " +
                                "letter capital case: " + address.getStateTwoLetterCode() + " Note stateName may be filled, or be " +
                                "null in but state code is required to comply with the pattern, stateName: " +
                                address.getStateTwoLetterCode();
                        constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                                .addPropertyNode("state").addConstraintViolation();
                        violationMessages.add(violationMessage);
                        rc = false;
                    }
                    if (StringUtils.isBlank(address.getZip4()) && StringUtils.isBlank(address.getZip5())) {
                        violationMessage = "Country is US, hence either zip5 / zip4 needs to be provided";
                        constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                                .addPropertyNode("zip4").addPropertyNode("zip5").addConstraintViolation();
                        violationMessages.add(violationMessage);
                        rc = false;
                    } else if (StringUtils.isNotBlank(address.getZip4()) && !ZIP4_PATTERN.matcher(address.getZip4())
                            .matches()) {
                        violationMessage = "Country is US and zip4 is provided, however zip4 does not comply with " +
                                "being numeric with the right number of digits. zip4:" + address.getZip4();
                        constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                                .addPropertyNode("zip4").addConstraintViolation();
                        violationMessages.add(violationMessage);
                        rc = false;
                    } else if (StringUtils.isNotBlank(address.getZip5()) && !ZIP5_PATTERN.matcher(address.getZip5())
                            .matches()) {
                        violationMessage = "Country is US and zip5 is provided, however zip5 does not comply with " +
                                "being numeric with the right number of digits. zip5:" + address.getZip5();
                        constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                                .addPropertyNode("zip5").addConstraintViolation();
                        violationMessages.add(violationMessage);
                        rc = false;
                    }
                } else {
                    if (StringUtils.isBlank(address.getPostalCode())) {
                        violationMessage = "Country is international, yet postal code is missing postalCode:" +
                                address.getPostalCode() + ".";
                        constraintValidatorContext.buildConstraintViolationWithTemplate(violationMessage)
                                .addPropertyNode("postalCode").addConstraintViolation();
                        violationMessages.add(violationMessage);
                        rc = false;
                    }
                }
            }
            if (!rc) {
                address.setValidationMessage(String.valueOf(violationMessages));
            }
            address.setLastValidationAttempt(new Date());
        }

        return rc;
    }
}
