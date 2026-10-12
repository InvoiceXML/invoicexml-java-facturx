package com.invoicexml.facturx;

import com.invoicexml.facturx.model.BuyerParty;
import com.invoicexml.facturx.model.BuyerPostalAddress;
import com.invoicexml.facturx.model.InvoiceDocument;
import com.invoicexml.facturx.model.InvoiceLine;
import com.invoicexml.facturx.model.ItemInformation;
import com.invoicexml.facturx.model.LineVatInformation;
import com.invoicexml.facturx.model.PaymentDetails;
import com.invoicexml.facturx.model.PriceDetails;
import com.invoicexml.facturx.model.SellerLegalRegistration;
import com.invoicexml.facturx.model.SellerParty;
import com.invoicexml.facturx.model.SellerPostalAddress;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

final class Fixtures {

    private Fixtures() {
    }

    static InvoiceDocument invoice() {
        return new InvoiceDocument()
                .setInvoiceNumber("FA-2026-0042")
                .setIssueDate(LocalDate.of(2026, 9, 1))
                .setCurrency("EUR")
                .setSeller(new SellerParty()
                        .setName("Atelier Numerique SARL")
                        .setVatIdentifier("FR32123456789")
                        .setLegalRegistration(SellerLegalRegistration.of("123456789", "0002"))
                        .setPostalAddress(new SellerPostalAddress()
                                .setLine1("18 Rue de la Republique").setCity("Lyon").setPostCode("69002").setCountry("FR")))
                .setBuyer(new BuyerParty()
                        .setName("Librairie Grand Siecle SAS")
                        .setPostalAddress(new BuyerPostalAddress()
                                .setLine1("5 Boulevard Saint-Germain").setCity("Paris").setPostCode("75005").setCountry("FR")))
                .setPaymentDetails(new PaymentDetails().setPaymentAccountIdentifier("FR7630006000011234567890189"))
                .addLine(new InvoiceLine()
                        .setQuantity(new BigDecimal("12"))
                        .setItem(new ItemInformation().setName("Maintenance applicative"))
                        .setPriceDetails(new PriceDetails().setNetPrice(new BigDecimal("180.00")))
                        .setVatInformation(new LineVatInformation().setRate(new BigDecimal("20"))))
                .addLine(new InvoiceLine()
                        .setQuantity(new BigDecimal("200"))
                        .setItem(new ItemInformation().setName("Guide utilisateur imprime"))
                        .setPriceDetails(new PriceDetails().setNetPrice(new BigDecimal("4.50")))
                        .setVatInformation(new LineVatInformation().setRate(new BigDecimal("5.5"))));
    }

    static String resource(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
