package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.Coordinate;
import com.e2eq.framework.model.persistent.base.MailingAddress;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;

import jakarta.inject.Inject;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TestSerialization {
    @Inject
    ObjectMapper mapper;
    @Test
    public void testMailingAddressSerialization() throws JsonProcessingException {

        MailingAddress mailingAddress = MailingAddress.builder()
                .city("city")
                .countryTwoLetterCode("US")
                .stateTwoLetterCode("GA")
                .addressLine1("any street 1")
                .zip4("30022")
                .coordinates(Coordinate.builder()
                        .latitude(1.0)
                        .longitude(1.0)
                        .position(new double[]{1.0, 1.0})
                        .build())
                .build();

       String value = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mailingAddress);
       System.out.println(value);
       MailingAddress address = mapper.readerFor(MailingAddress.class).readValue(value);
       Assertions.assertTrue(mailingAddress.equals(address));
    }


}
