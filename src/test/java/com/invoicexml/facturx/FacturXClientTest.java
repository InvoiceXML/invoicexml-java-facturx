package com.invoicexml.facturx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicexml.facturx.model.CreateInvoiceRequest;
import com.invoicexml.facturx.model.InvoiceDocument;
import com.invoicexml.facturx.model.InvoiceOptions;
import com.invoicexml.facturx.model.PdfLanguage;
import com.invoicexml.facturx.model.RuleSet;
import com.invoicexml.facturx.transport.TransportRequest;
import com.invoicexml.facturx.validation.ValidationFinding;
import com.invoicexml.facturx.validation.ValidationResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FacturXClientTest {

    private static final byte[] PDF = "%PDF-1.7 stub".getBytes(StandardCharsets.US_ASCII);
    private static final String XML = "<?xml version=\"1.0\"?><rsm:CrossIndustryInvoice/>";

    private final StubTransport stub = new StubTransport();
    private final List<Duration> sleeps = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    private FacturXClient client() {
        return FacturXClient.builder()
                .apiKey("test-key")
                .transport(stub)
                .sleeper(sleeps::add)
                .clock(Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC))
                .build();
    }

    private static String body(TransportRequest r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------- create

    @Test
    void createFacturXPostsJsonAndReturnsPdf() throws Exception {
        stub.respond(200, "application/pdf", PDF);

        byte[] pdf = client().createFacturX(Fixtures.invoice());

        assertArrayEquals(PDF, pdf);
        TransportRequest r = stub.last();
        assertEquals("POST", r.method());
        assertEquals("https://api.invoicexml.com/v1/create/facturx", r.uri().toString());
        assertEquals("Bearer test-key", r.header("Authorization"));
        assertEquals("application/json", r.header("Content-Type"));
        assertTrue(r.header("User-Agent").startsWith("invoicexml-java-facturx/"));

        JsonNode sent = json.readTree(r.body());
        JsonNode invoice = sent.get("invoice");
        assertEquals("FA-2026-0042", invoice.get("invoiceNumber").asText());
        assertEquals("2026-09-01", invoice.get("issueDate").asText());
        assertEquals("FR", invoice.at("/seller/postalAddress/country").asText());
        assertEquals("0002", invoice.at("/seller/legalRegistration/schemeId").asText());
        assertTrue(body(r).contains("\"netPrice\":4.50"), "BigDecimal scale must be kept");
        assertTrue(body(r).contains("\"rate\":5.5"));
        assertFalse(sent.has("options"), "null options must be omitted");
        assertFalse(invoice.has("dueDate"), "null fields must be omitted");
    }

    @Test
    void createFacturXSendsRulesAndRenderingOptions() throws Exception {
        stub.respond(200, "application/pdf", PDF);

        client().createFacturX(Fixtures.invoice(), new InvoiceOptions()
                .addRule(RuleSet.BR_FR)
                .setLanguage(PdfLanguage.FR)
                .setBrandColor("#0A7C66"));

        JsonNode options = json.readTree(stub.last().body()).get("options");
        assertEquals(1, options.get("rules").size());
        assertEquals("br-fr", options.get("rules").get(0).asText());
        assertEquals("FR", options.get("language").asText());
        assertEquals("#0A7C66", options.get("brandColor").asText());
        assertFalse(options.has("syntax"));
        assertFalse(options.has("profile"));
    }

    @Test
    void createFacturXSendsProfileIdentifier() throws Exception {
        stub.respond(200, "application/pdf", PDF);

        client().createFacturX(Fixtures.invoice().setSpecificationId(FacturXProfiles.BASIC));

        assertEquals(FacturXProfiles.BASIC, json.readTree(stub.last().body()).at("/invoice/specificationId").asText());
    }

    @Test
    void createRejectionCarriesFindings() {
        stub.respondJson(400, Fixtures.resource("create-400.json"));

        InvoiceValidationException e = assertThrows(InvoiceValidationException.class,
                () -> client().createFacturX(Fixtures.invoice()));

        assertEquals(400, e.getStatusCode());
        assertEquals(ErrorCodes.MODEL_VALIDATION, e.getErrorCode());
        assertEquals("00-abc-def-01", e.getTraceId());
        assertEquals(1, e.getErrors().size());
        ValidationFinding f = e.getErrors().get(0);
        assertEquals("BR-CO-26", f.getRule());
        assertEquals(ValidationFinding.LAYER_EN16931, f.getLayer());
        assertEquals(List.of("seller.vatIdentifier"), f.getFields());
        assertEquals(List.of(), f.getAdditionalProperties().get("btCodes"));
        assertNull(f.getLine());
        assertEquals("380", e.getInvoiceData().getTypeCode());
        assertTrue(e.getMessage().contains("BR-CO-26"));
    }

    @Test
    void createRequiresARequest() {
        assertThrows(NullPointerException.class, () -> client().createFacturX((CreateInvoiceRequest) null));
    }

    // ----------------------------------------------------------- validate

    @Test
    void validateFacturXUploadsFileAndParsesResult(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("facture.pdf");
        Files.write(pdf, PDF);
        stub.respondJson(200, Fixtures.resource("validate-facturx-valid.json"));

        ValidationResult result = client().validateFacturX(pdf);

        assertTrue(result.isValid());
        assertEquals("en16931", result.getData().getProfile());
        assertTrue(result.getData().getHasEmbeddedXml());
        assertTrue(result.getErrors().isEmpty());
        assertEquals("invoiceNumber", result.getReport().get(0).getPath());
        assertTrue(result.getReport().get(0).isValid());

        TransportRequest r = stub.last();
        assertEquals("/v1/validate/facturx", r.uri().getPath());
        assertEquals("application/json", r.header("Accept"));
        String contentType = r.header("Content-Type");
        assertTrue(contentType.startsWith("multipart/form-data; boundary="));
        String body = body(r);
        assertTrue(body.contains("Content-Disposition: form-data; name=\"file\"; filename=\"facture.pdf\""));
        assertTrue(body.contains("Content-Type: application/pdf"));
        assertFalse(body.contains("name=\"rules\""));
        String boundary = contentType.substring(contentType.indexOf('=') + 1);
        assertTrue(body.endsWith("--" + boundary + "--\r\n"));
    }

    @Test
    void validateFacturXSendsRuleSets() {
        stub.respondJson(200, Fixtures.resource("validate-facturx-valid.json"));

        client().validateFacturX(FileUpload.ofPdf(PDF), new ValidateOptions().addRule(RuleSet.BR_FR));

        assertEquals(1, count(body(stub.last()), "name=\"rules\"\r\n\r\nbr-fr\r\n"));
    }

    @Test
    void invalidInvoiceIsAResultNotAnException() {
        stub.respondJson(200, Fixtures.resource("validate-facturx-invalid.json"));

        ValidationResult result = client().validateFacturX(FileUpload.ofPdf(PDF));

        assertFalse(result.isValid());
        assertFalse(result.getData().getSchematronValid());
        assertEquals("basic", result.getData().getProfile());
        ValidationFinding error = result.getErrors().get(0);
        assertEquals("BR-CO-10", error.getRule());
        assertEquals(List.of("totals.sumOfLineNetAmounts"), error.getFields());
        assertEquals(Integer.valueOf(2), result.getWarnings().get(0).getLine());
        assertFalse(result.getReport().get(0).isValid());
        assertEquals("BR-CO-10", result.getReport().get(0).getErrors().get(0).getRule());
    }

    // -------------------------------------------------------------- embed

    @Test
    void embedFacturXSendsPdfAndXmlParts() {
        stub.respond(200, "application/pdf", PDF);

        byte[] out = client().embedFacturX(FileUpload.ofPdf(PDF), FileUpload.ofXml(XML));

        assertArrayEquals(PDF, out);
        String body = body(stub.last());
        assertEquals("/v1/embed/facturx", stub.last().uri().getPath());
        assertTrue(body.contains("name=\"pdf\"; filename=\"invoice.pdf\""));
        assertTrue(body.contains("name=\"xml\"; filename=\"invoice.xml\""));
        assertTrue(body.contains("Content-Type: application/xml"));
        assertFalse(body.contains("skipValidation"));
        assertFalse(body.contains("name=\"rules\""));
    }

    @Test
    void embedFacturXSendsOptions() {
        stub.respond(200, "application/pdf", PDF);

        client().embedFacturX(FileUpload.ofPdf(PDF), FileUpload.ofXml(XML),
                new EmbedOptions().setSkipValidation(true).addRule(RuleSet.BR_FR));

        String body = body(stub.last());
        assertTrue(body.contains("name=\"skipValidation\"\r\n\r\ntrue\r\n"));
        assertTrue(body.contains("name=\"rules\"\r\n\r\nbr-fr\r\n"));
    }

    // ------------------------------------------------------------ extract

    @Test
    void extractJsonReturnsTheInvoiceModel() {
        stub.respondJson(200, Fixtures.resource("extract-json.json"));

        InvoiceDocument invoice = client().extractJson(FileUpload.ofPdf(PDF));

        assertEquals("/v1/extract/json", stub.last().uri().getPath());
        assertEquals("FA-2026-0042", invoice.getInvoiceNumber());
        assertEquals(LocalDate.of(2026, 9, 1), invoice.getIssueDate());
        assertEquals("380", invoice.getTypeCode());
        assertEquals("Plain note", invoice.getNotes().get(0).getNote());
        assertEquals("AAB", invoice.getNotes().get(1).getSubjectCode());
        assertEquals("0002", invoice.getSeller().getLegalRegistration().getSchemeId());
        assertEquals("EM", invoice.getSeller().getElectronicAddress().getSchemeId());
        assertEquals("58", invoice.getPaymentDetails().getPaymentMeansCode());
        assertEquals(0, new BigDecimal("3541.50").compareTo(invoice.getTotals().getGrandTotalAmount()));
        assertEquals(0, new BigDecimal("5.5").compareTo(invoice.getVatBreakdowns().get(1).getRate()));
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII),
                invoice.getSupportingDocuments().get(0).getAttachment());
    }

    @Test
    void extractAttachmentsReturnsZip() {
        byte[] zip = {0x50, 0x4b, 0x03, 0x04};
        stub.respond(200, "application/zip", zip);

        assertArrayEquals(zip, client().extractAttachments(FileUpload.ofPdf(PDF)));
        assertEquals("/v1/extract/attachments", stub.last().uri().getPath());
    }

    // ------------------------------------------------------------ retries

    @Test
    void retriesAfterRateLimitUsingRetryAfterSeconds() {
        stub.respondJson(429, "{\"title\":\"Too many requests\",\"errorCode\":4019}", "Retry-After", "2")
            .respond(200, "application/pdf", PDF);

        assertArrayEquals(PDF, client().createFacturX(Fixtures.invoice()));
        assertEquals(List.of(Duration.ofSeconds(2)), sleeps);
        assertEquals(2, stub.requests.size());
        assertArrayEquals(stub.requests.get(0).body(), stub.requests.get(1).body(), "retry must resend the same body");
    }

    @Test
    void retryAfterHttpDateIsHonoured() {
        stub.respondJson(429, "{}", "Retry-After", "Fri, 09 Oct 2026 12:00:05 GMT")
            .respond(200, "application/pdf", PDF);

        client().createFacturX(Fixtures.invoice());

        assertEquals(List.of(Duration.ofSeconds(5)), sleeps);
    }

    @Test
    void rateLimitSurfacesAfterRetriesAreUsedUp() {
        for (int i = 0; i < 4; i++) {
            stub.respondJson(429, "{\"title\":\"Too many requests\",\"errorCode\":4019}", "Retry-After", "1");
        }

        RateLimitException e = assertThrows(RateLimitException.class, () -> client().createFacturX(Fixtures.invoice()));

        assertEquals(4, stub.requests.size());
        assertEquals(3, sleeps.size());
        assertEquals(Duration.ofSeconds(1), e.getRetryAfter());
        assertEquals(ErrorCodes.TOO_MANY_REQUESTS, e.getErrorCode());
    }

    @Test
    void retryAfterLongerThanTheCapFailsFast() {
        stub.respondJson(429, "{}", "Retry-After", "600");

        assertThrows(RateLimitException.class, () -> client().createFacturX(Fixtures.invoice()));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void missingRetryAfterFallsBackToBackoff() {
        stub.respondJson(429, "{}").respondJson(429, "{}").respond(200, "application/pdf", PDF);

        client().createFacturX(Fixtures.invoice());

        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps);
    }

    // ------------------------------------------------------------- errors

    @Test
    void unauthorizedMapsToAuthenticationException() {
        stub.respondJson(401, "{\"title\":\"Unauthorized\",\"detail\":\"Invalid or inactive API key.\",\"errorCode\":4010}");

        AuthenticationException e = assertThrows(AuthenticationException.class,
                () -> client().validateFacturX(FileUpload.ofPdf(PDF)));
        assertEquals(ErrorCodes.UNAUTHORIZED, e.getErrorCode());
        assertEquals("Invalid or inactive API key.", e.getDetail());
    }

    @Test
    void payloadTooLargeMapsToItsOwnException() {
        stub.respondJson(413, "{\"title\":\"Payload too large\",\"errorCode\":4005}");

        assertThrows(PayloadTooLargeException.class, () -> client().validateFacturX(FileUpload.ofPdf(PDF)));
    }

    @Test
    void otherProblemsMapToApiException() {
        stub.respondJson(400, "{\"title\":\"Missing file\",\"detail\":\"No PDF file was provided in the request.\",\"errorCode\":4003}");

        InvoiceXmlApiException e = assertThrows(InvoiceXmlApiException.class,
                () -> client().validateFacturX(FileUpload.ofPdf(new byte[0])));
        assertFalse(e instanceof InvoiceValidationException);
        assertEquals(ErrorCodes.MISSING_FILE, e.getErrorCode());
        assertTrue(e.getMessage().contains("Missing file"));
    }

    @Test
    void nonJsonErrorBodyStillProducesAnException() {
        stub.respond(500, "text/html", "<html>oops</html>".getBytes(StandardCharsets.UTF_8));

        InvoiceXmlApiException e = assertThrows(InvoiceXmlApiException.class,
                () -> client().createFacturX(Fixtures.invoice()));
        assertEquals(500, e.getStatusCode());
        assertNull(e.getErrorCode());
        assertEquals("<html>oops</html>", e.getResponseBody());
    }

    @Test
    void ioFailureMapsToConnectionException() {
        IOException cause = new IOException("connection reset");
        stub.fail(cause);

        InvoiceXmlConnectionException e = assertThrows(InvoiceXmlConnectionException.class,
                () -> client().createFacturX(Fixtures.invoice()));
        assertSame(cause, e.getCause());
    }

    // ------------------------------------------------------------ builder

    @Test
    void builderRequiresApiKey() {
        assertThrows(IllegalArgumentException.class, () -> FacturXClient.builder().build());
        assertThrows(IllegalArgumentException.class, () -> FacturXClient.create(" "));
    }

    @Test
    void baseUrlTrailingSlashIsIgnored() {
        stub.respond(200, "application/pdf", PDF);
        FacturXClient c = FacturXClient.builder()
                .apiKey("k").baseUrl("http://localhost:5000/").transport(stub).build();

        c.createFacturX(Fixtures.invoice());

        assertEquals("http://localhost:5000/v1/create/facturx", stub.last().uri().toString());
    }

    @Test
    void versionIsFilteredFromThePom() {
        assertFalse(FacturXClient.VERSION.startsWith("$"));
    }
}
