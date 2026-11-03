package com.invoicexml.facturx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicexml.facturx.email.EmailMessage;
import com.invoicexml.facturx.model.InvoiceDocument;
import com.invoicexml.facturx.model.InvoiceNote;
import com.invoicexml.facturx.model.InvoiceOptions;
import com.invoicexml.facturx.model.RuleSet;
import com.invoicexml.facturx.model.SellerIdentifier;
import com.invoicexml.facturx.model.UblProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
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
    void ublProfileRoundTrips() throws Exception {
        String json = mapper.writeValueAsString(new InvoiceOptions().setProfile(UblProfile.PEPPOL_BIS_3));
        assertEquals("{\"profile\":\"peppol-bis-3\"}", json);
        assertEquals(UblProfile.PEPPOL_BIS_3, mapper.readValue(json, InvoiceOptions.class).getProfile());
        assertEquals(List.of("en16931", "peppol-bis-3", "nlcius", "ehf", "xrechnung", "pint"),
                Arrays.stream(UblProfile.values()).map(UblProfile::getValue).collect(Collectors.toList()));
        assertEquals(UblProfile.XRECHNUNG, UblProfile.fromValue("XRechnung"));
        assertThrows(IllegalArgumentException.class, () -> UblProfile.fromValue("pint-sg"));
    }

    @Test
    void identifierFactories() {
        assertEquals("0009", SellerIdentifier.of("12345678900012", "0009").getSchemeId());
        assertNull(SellerIdentifier.of("12345678900012").getSchemeId());
    }

    @Test
    void emailMessageOmitsUnsetFieldsAndRoundTrips() throws Exception {
        EmailMessage required = EmailMessage.of("factures@example.com", "compta@example.org");
        assertEquals("{\"from\":\"factures@example.com\",\"to\":\"compta@example.org\"}", mapper.writeValueAsString(required));

        EmailMessage original = EmailMessage.of("factures@example.com", "compta@example.org")
                .setFromName("Facturation")
                .addCc("cc@example.org")
                .addBcc("bcc@example.org")
                .setReplyTo("aide@example.com")
                .setSubject("Facture FA-2026-0042")
                .setMessage("Bonjour");

        EmailMessage copy = mapper.readValue(mapper.writeValueAsBytes(original), EmailMessage.class);

        assertEquals(original, copy);
        assertEquals(original.hashCode(), copy.hashCode());
    }

    @Test
    void emailMessageToStringRevealsNoAddressOrText() {
        String printed = EmailMessage.of("factures@example.com", "compta@example.org")
                .setFromName("Facturation")
                .addCc("cc@example.org")
                .addBcc("bcc@example.org")
                .setReplyTo("aide@example.com")
                .setSubject("Facture FA-2026-0042")
                .setMessage("Bonjour")
                .toString();

        for (String secret : List.of("factures@example.com", "compta@example.org", "Facturation", "cc@example.org",
                "bcc@example.org", "aide@example.com", "Facture FA-2026-0042", "Bonjour")) {
            assertFalse(printed.contains(secret), secret);
        }
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
