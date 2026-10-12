package com.invoicexml.facturx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicexml.facturx.model.InvoiceDocument;
import com.invoicexml.facturx.model.InvoiceNote;
import com.invoicexml.facturx.model.InvoiceOptions;
import com.invoicexml.facturx.model.RuleSet;
import com.invoicexml.facturx.model.SellerIdentifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ModelTest {

    private final ObjectMapper mapper = Json.newMapper();

    @Test
    void invoiceRoundTripsThroughJson() throws Exception {
        InvoiceDocument original = Fixtures.invoice()
                .addNote("Plain note")
                .addNote(InvoiceNote.of("Indemnite forfaitaire pour frais de recouvrement : 40 EUR", "PMT"));

        InvoiceDocument copy = mapper.readValue(mapper.writeValueAsBytes(original), InvoiceDocument.class);

        assertEquals(original, copy);
        assertEquals(original.hashCode(), copy.hashCode());
    }

    @Test
    void notesAreSentAsObjects() throws Exception {
        String json = mapper.writeValueAsString(new InvoiceDocument().addNote(InvoiceNote.of("Penalites", "PMD")));
        assertEquals("{\"notes\":[{\"note\":\"Penalites\",\"subjectCode\":\"PMD\"}]}", json);
    }

    @Test
    void rulesRoundTrip() throws Exception {
        String json = mapper.writeValueAsString(new InvoiceOptions().addRule(RuleSet.BR_FR));
        assertEquals("{\"rules\":[\"br-fr\"]}", json);
        assertEquals(RuleSet.BR_FR, mapper.readValue(json, InvoiceOptions.class).getRules().get(0));
        assertEquals(RuleSet.BR_FR, RuleSet.fromValue("BR-FR"));
        assertThrows(IllegalArgumentException.class, () -> RuleSet.fromValue("br-de"));
    }

    @Test
    void identifierFactories() {
        assertEquals("0009", SellerIdentifier.of("12345678900012", "0009").getSchemeId());
        assertNull(SellerIdentifier.of("12345678900012").getSchemeId());
    }

    @Test
    void retryAfterParsing() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC);
        assertEquals(Duration.ofSeconds(7), RetryAfter.parse(" 7 ", clock));
        assertEquals(Duration.ZERO, RetryAfter.parse("Fri, 09 Oct 2026 11:00:00 GMT", clock));
        assertNull(RetryAfter.parse("soon", clock));
        assertNull(RetryAfter.parse("-3", clock));
        assertNull(RetryAfter.parse(null, clock));
    }
}
