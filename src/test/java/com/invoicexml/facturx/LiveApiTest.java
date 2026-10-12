package com.invoicexml.facturx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.invoicexml.facturx.model.AdditionalSupportingDocument;
import com.invoicexml.facturx.model.BuyerElectronicAddress;
import com.invoicexml.facturx.model.BuyerLegalRegistration;
import com.invoicexml.facturx.model.InvoiceDocument;
import com.invoicexml.facturx.model.InvoiceNote;
import com.invoicexml.facturx.model.InvoiceOptions;
import com.invoicexml.facturx.model.PdfLanguage;
import com.invoicexml.facturx.model.RuleSet;
import com.invoicexml.facturx.model.SellerElectronicAddress;
import com.invoicexml.facturx.validation.ValidationResult;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Calls the real API. Consumes credits, so it only runs when both the
 * {@code INVOICEXML_API_KEY} environment variable and {@code -Dinvoicexml.live=true} are set:
 * <pre>
 * mvn verify -Dinvoicexml.live=true
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "INVOICEXML_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "invoicexml.live", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveApiTest {

    private static final String BASE_URL = System.getProperty("invoicexml.baseUrl", FacturXClient.DEFAULT_BASE_URL);
    private static final FacturXClient CLIENT = FacturXClient.builder()
            .apiKey(System.getenv("INVOICEXML_API_KEY"))
            .baseUrl(BASE_URL)
            .build();

    private static byte[] facturxPdf;
    private static byte[] mandatePdf;

    /** The sample invoice with the details the French mandate's BR-FR rules ask for. */
    private static InvoiceDocument mandateInvoice() {
        InvoiceDocument invoice = Fixtures.invoice()
                .setBusinessProcessType("M1")
                .addNote(InvoiceNote.of("Indemnite forfaitaire pour frais de recouvrement : 40 EUR", "PMT"))
                .addNote(InvoiceNote.of("Penalites de retard : trois fois le taux d'interet legal", "PMD"))
                .addNote(InvoiceNote.of("Pas d'escompte pour paiement anticipe", "AAB"));
        invoice.getSeller().setElectronicAddress(SellerElectronicAddress.of("123456789", "0225"));
        invoice.getBuyer()
                .setLegalRegistration(BuyerLegalRegistration.of("987654321", "0002"))
                .setElectronicAddress(BuyerElectronicAddress.of("987654321", "0225"));
        return invoice;
    }

    private static String describe(ValidationResult r) {
        return r.getDetail() + " " + r.getErrors().stream().map(Object::toString).collect(Collectors.joining("; "));
    }

    private static void assertPdf(byte[] bytes) {
        assertTrue(bytes.length > 1000, "PDF too small: " + bytes.length);
        assertEquals("%PDF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    @Order(1)
    void createFacturX() {
        facturxPdf = CLIENT.createFacturX(Fixtures.invoice(), new InvoiceOptions().setLanguage(PdfLanguage.FR));
        assertPdf(facturxPdf);
    }

    @Test
    @Order(2)
    void validateFacturX() {
        ValidationResult result = CLIENT.validateFacturX(FileUpload.ofPdf(facturxPdf));
        assertTrue(result.isValid(), describe(result));
        assertTrue(result.getData().getHasEmbeddedXml());
        assertEquals("en16931", result.getData().getProfile());
        assertFalse(result.getReport().isEmpty());
    }

    @Test
    @Order(3)
    void extractJson() {
        InvoiceDocument invoice = CLIENT.extractJson(FileUpload.ofPdf(facturxPdf));
        assertEquals("FA-2026-0042", invoice.getInvoiceNumber());
        assertEquals("FR", invoice.getSeller().getPostalAddress().getCountry());
        assertEquals(2, invoice.getLines().size());
        assertEquals(0, invoice.getTotals().getGrandTotalAmount().compareTo(new BigDecimal("3541.50")));
    }

    @Test
    @Order(4)
    void createWithFrenchMandateRules() {
        try {
            mandatePdf = CLIENT.createFacturX(mandateInvoice(), new InvoiceOptions().addRule(RuleSet.BR_FR));
        } catch (InvoiceValidationException e) {
            throw new AssertionError(e.getMessage(), e);
        }
        assertPdf(mandatePdf);
    }

    @Test
    @Order(5)
    void validateWithFrenchMandateRules() {
        ValidationResult result = CLIENT.validateFacturX(FileUpload.ofPdf(mandatePdf),
                new ValidateOptions().addRule(RuleSet.BR_FR));
        assertTrue(result.isValid(), describe(result));
    }

    @Test
    @Order(6)
    void createBasicProfile() {
        byte[] pdf = CLIENT.createFacturX(Fixtures.invoice().setSpecificationId(FacturXProfiles.BASIC));
        ValidationResult result = CLIENT.validateFacturX(FileUpload.ofPdf(pdf));
        assertTrue(result.isValid(), describe(result));
        assertEquals("basic", result.getData().getProfile());
        assertEquals(FacturXProfiles.BASIC, result.getData().getCustomizationId());
    }

    @Test
    @Order(7)
    void embedIntoOwnPdf() throws Exception {
        byte[] xml = extractXml(facturxPdf);
        byte[] hybrid = CLIENT.embedFacturX(FileUpload.ofPdf(blankPdf()), FileUpload.ofXml(xml));
        assertPdf(hybrid);
        ValidationResult result = CLIENT.validateFacturX(FileUpload.ofPdf(hybrid));
        assertTrue(result.isValid(), describe(result));
    }

    @Test
    @Order(8)
    void extractAttachments() {
        InvoiceDocument invoice = Fixtures.invoice().addSupportingDocument(new AdditionalSupportingDocument()
                .setReference("BL-77")
                .setDescription("Bon de livraison")
                .setAttachment("ligne;quantite\n1;12\n".getBytes(StandardCharsets.UTF_8))
                .setAttachmentMimeCode("text/csv")
                .setAttachmentFilename("bon-de-livraison.csv"));
        byte[] pdf = CLIENT.createFacturX(invoice);

        byte[] zip = CLIENT.extractAttachments(FileUpload.ofPdf(pdf));
        assertEquals("PK", new String(zip, 0, 2, StandardCharsets.US_ASCII));
    }

    @Test
    @Order(9)
    void rejectedInvoiceCarriesFindings() {
        InvoiceDocument invalid = Fixtures.invoice();
        invalid.getSeller().setVatIdentifier(null).setLegalRegistration(null);

        InvoiceValidationException e = assertThrows(InvoiceValidationException.class,
                () -> CLIENT.createFacturX(invalid));
        assertEquals(400, e.getStatusCode());
        assertFalse(e.getErrors().isEmpty(), e.getMessage());
        assertNotNull(e.getErrors().get(0).getMessage());
    }

    @Test
    @Order(10)
    void invalidKeyIsRejected() {
        FacturXClient bad = FacturXClient.builder().apiKey("invalid-key").baseUrl(BASE_URL).build();
        AuthenticationException e = assertThrows(AuthenticationException.class,
                () -> bad.validateFacturX(FileUpload.ofPdf(facturxPdf)));
        assertEquals(ErrorCodes.UNAUTHORIZED, e.getErrorCode());
    }

    // ------------------------------------------------------------ helpers

    /** Reads the embedded CII XML of a hybrid PDF through the API's extract/xml endpoint (test-only). */
    private static byte[] extractXml(byte[] pdf) throws Exception {
        String boundary = "test-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"invoice.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(pdf);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/v1/extract/xml"))
                .header("Authorization", "Bearer " + System.getenv("INVOICEXML_API_KEY"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        return response.body();
    }

    /** A minimal single-page A4 PDF with a drawn frame and no fonts. */
    private static byte[] blankPdf() {
        String content = "0.5 w 50 50 495.28 741.89 re S";
        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595.28 841.89] /Contents 4 0 R /Resources << >> >>");
        objects.add("<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream");

        StringBuilder pdf = new StringBuilder("%PDF-1.7\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
