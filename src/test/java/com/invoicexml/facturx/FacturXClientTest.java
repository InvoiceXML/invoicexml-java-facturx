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
import com.invoicexml.facturx.email.EmailMessage;
import com.invoicexml.facturx.email.EmailSendResult;
import com.invoicexml.facturx.model.CreateInvoiceRequest;
import com.invoicexml.facturx.model.InvoiceDocument;
import com.invoicexml.facturx.model.InvoiceOptions;
import com.invoicexml.facturx.model.PdfLanguage;
import com.invoicexml.facturx.model.RuleSet;
import com.invoicexml.facturx.model.UblProfile;
import com.invoicexml.facturx.parse.ParsedInvoice;
import com.invoicexml.facturx.transport.TransportRequest;
import com.invoicexml.facturx.validation.ValidationFinding;
import com.invoicexml.facturx.validation.ValidationReport;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FacturXClientTest {

    private static final byte[] PDF = "%PDF-1.7 stub".getBytes(StandardCharsets.US_ASCII);
    private static final String XML = "<?xml version=\"1.0\"?><rsm:CrossIndustryInvoice/>";
    private static final String ACCEPTED = "{\"status\":\"accepted\",\"messageId\":\"0107019a-msg\"}";

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

    private static EmailMessage email() {
        return EmailMessage.of("factures@example.com", "compta@example.org");
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

    @Test
    void createCiiPostsJsonAndReturnsXml() throws Exception {
        stub.respond(200, "application/xml", XML.getBytes(StandardCharsets.UTF_8),
                "Content-Disposition", "attachment; filename=invoice-cii-FA-2026-0042.xml");

        String xml = client().createCii(Fixtures.invoice());

        assertEquals(XML, xml);
        TransportRequest r = stub.last();
        assertEquals("POST", r.method());
        assertEquals("https://api.invoicexml.com/v1/create/cii", r.uri().toString());
        assertEquals("application/json", r.header("Content-Type"));
        JsonNode sent = json.readTree(r.body());
        assertEquals("FA-2026-0042", sent.at("/invoice/invoiceNumber").asText());
        assertFalse(sent.has("options"), "null options must be omitted");
    }

    @Test
    void createCiiSendsLanguageAndRules() throws Exception {
        stub.respond(200, "application/xml", XML.getBytes(StandardCharsets.UTF_8));

        client().createCii(Fixtures.invoice(), new InvoiceOptions()
                .setLanguage(PdfLanguage.FR)
                .addRule(RuleSet.BR_FR));

        JsonNode options = json.readTree(stub.last().body()).get("options");
        assertEquals("FR", options.get("language").asText());
        assertEquals(1, options.get("rules").size());
        assertEquals("br-fr", options.get("rules").get(0).asText());
        assertEquals(2, options.size(), "unset options must be omitted");
    }

    @Test
    void createCiiTakesAFullRequestEnvelope() throws Exception {
        stub.respond(200, "application/xml", XML.getBytes(StandardCharsets.UTF_8));

        String xml = client().createCii(new CreateInvoiceRequest().setInvoice(Fixtures.invoice())
                .setOptions(new InvoiceOptions().setLanguage(PdfLanguage.DE)));

        assertEquals(XML, xml);
        assertEquals("/v1/create/cii", stub.last().uri().getPath());
        JsonNode sent = json.readTree(stub.last().body());
        assertEquals("FA-2026-0042", sent.at("/invoice/invoiceNumber").asText());
        assertEquals("DE", sent.at("/options/language").asText());
    }

    @Test
    void createUblPostsJsonAndReturnsXml() throws Exception {
        stub.respond(200, "application/xml", XML.getBytes(StandardCharsets.UTF_8),
                "Content-Disposition", "attachment; filename=invoice-ubl-FA-2026-0042.xml");

        String xml = client().createUbl(Fixtures.invoice());

        assertEquals(XML, xml);
        TransportRequest r = stub.last();
        assertEquals("POST", r.method());
        assertEquals("https://api.invoicexml.com/v1/create/ubl", r.uri().toString());
        assertEquals("application/json", r.header("Content-Type"));
        JsonNode sent = json.readTree(r.body());
        assertEquals("FA-2026-0042", sent.at("/invoice/invoiceNumber").asText());
        assertFalse(sent.has("options"), "null options must be omitted");
    }

    @Test
    void createUblSendsProfileLanguageAndRules() throws Exception {
        stub.respond(200, "application/xml", XML.getBytes(StandardCharsets.UTF_8));

        client().createUbl(Fixtures.invoice(), new InvoiceOptions()
                .setProfile(UblProfile.PEPPOL_BIS_3)
                .setLanguage(PdfLanguage.FR)
                .addRule(RuleSet.BR_FR));

        JsonNode options = json.readTree(stub.last().body()).get("options");
        assertEquals("peppol-bis-3", options.get("profile").asText());
        assertEquals("FR", options.get("language").asText());
        assertEquals("br-fr", options.get("rules").get(0).asText());
        assertEquals(3, options.size(), "unset options must be omitted");
    }

    @Test
    void createUblTakesAFullRequestEnvelope() throws Exception {
        stub.respond(200, "application/xml", XML.getBytes(StandardCharsets.UTF_8));

        client().createUbl(new CreateInvoiceRequest().setInvoice(Fixtures.invoice())
                .setOptions(new InvoiceOptions().setProfile(UblProfile.NLCIUS)));

        assertEquals("/v1/create/ubl", stub.last().uri().getPath());
        assertEquals("nlcius", json.readTree(stub.last().body()).at("/options/profile").asText());
    }

    @Test
    void createCiiAndUblRejectionCarriesFindings() {
        stub.respondJson(400, Fixtures.resource("create-400.json"));
        stub.respondJson(400, Fixtures.resource("create-400.json"));

        InvoiceValidationException cii = assertThrows(InvoiceValidationException.class,
                () -> client().createCii(Fixtures.invoice()));
        assertEquals("/v1/create/cii", stub.last().uri().getPath());
        InvoiceValidationException ubl = assertThrows(InvoiceValidationException.class,
                () -> client().createUbl(Fixtures.invoice()));
        assertEquals("/v1/create/ubl", stub.last().uri().getPath());

        for (InvoiceValidationException e : List.of(cii, ubl)) {
            assertEquals(ErrorCodes.MODEL_VALIDATION, e.getErrorCode());
            assertEquals("BR-CO-26", e.getErrors().get(0).getRule());
        }
    }

    @Test
    void createCiiAndUblRequireARequest() {
        assertThrows(NullPointerException.class, () -> client().createCii((CreateInvoiceRequest) null));
        assertThrows(NullPointerException.class, () -> client().createUbl((CreateInvoiceRequest) null));
        assertTrue(stub.requests.isEmpty());
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

    // -------------------------------------------------------------- email

    @Test
    void emailFacturXPostsInvoiceAndEmailAndParsesResult() throws Exception {
        stub.respondJson(200, ACCEPTED);

        EmailSendResult result = client().emailFacturX(Fixtures.invoice(), email());

        assertEquals("accepted", result.getStatus());
        assertEquals("0107019a-msg", result.getMessageId());
        TransportRequest r = stub.last();
        assertEquals("POST", r.method());
        assertEquals("https://api.invoicexml.com/v1/create/facturx/email", r.uri().toString());
        assertEquals("application/json", r.header("Content-Type"));
        assertEquals("application/json", r.header("Accept"));

        JsonNode sent = json.readTree(r.body());
        assertEquals(Fixtures.invoice().getInvoiceNumber(), sent.at("/invoice/invoiceNumber").asText());
        assertFalse(sent.has("options"), "null options must be omitted");
        JsonNode email = sent.get("email");
        assertEquals(2, email.size(), "unset email fields must be omitted");
        assertEquals("factures@example.com", email.get("from").asText());
        assertEquals("compta@example.org", email.get("to").asText());
    }

    @Test
    void emailFacturXSendsOptionsAndEveryEmailField() throws Exception {
        stub.respondJson(200, ACCEPTED);

        client().emailFacturX(Fixtures.invoice(), email()
                        .setFromName("Facturation")
                        .addCc("cc@example.org")
                        .addBcc("bcc@example.org")
                        .setReplyTo("aide@example.com")
                        .setSubject("Facture FA-2026-0042")
                        .setMessage("Bonjour"),
                new InvoiceOptions().setLanguage(PdfLanguage.FR).addRule(RuleSet.BR_FR));

        JsonNode sent = json.readTree(stub.last().body());
        assertEquals("FR", sent.at("/options/language").asText());
        assertEquals("br-fr", sent.at("/options/rules/0").asText());
        JsonNode email = sent.get("email");
        assertEquals("Facturation", email.get("fromName").asText());
        assertEquals("cc@example.org", email.at("/cc/0").asText());
        assertEquals("bcc@example.org", email.at("/bcc/0").asText());
        assertEquals("aide@example.com", email.get("replyTo").asText());
        assertEquals("Facture FA-2026-0042", email.get("subject").asText());
        assertEquals("Bonjour", email.get("message").asText());
    }

    @Test
    void emailFacturXTakesAFullRequestEnvelope() throws Exception {
        stub.respondJson(200, ACCEPTED);

        client().emailFacturX(new CreateInvoiceRequest().setInvoice(Fixtures.invoice()), email());

        JsonNode sent = json.readTree(stub.last().body());
        assertEquals(Fixtures.invoice().getInvoiceNumber(), sent.at("/invoice/invoiceNumber").asText());
        assertEquals("compta@example.org", sent.at("/email/to").asText());
    }

    @Test
    void emailEmbeddedFacturXSendsFilePartsAndEmailFields() {
        stub.respondJson(200, ACCEPTED);

        EmailSendResult result = client().emailEmbeddedFacturX(FileUpload.ofPdf(PDF), FileUpload.ofXml(XML), email()
                .setFromName("Facturation")
                .addCc("un@example.org")
                .addCc("deux@example.org")
                .addBcc("trois@example.org")
                .setReplyTo("aide@example.com")
                .setSubject("Facture FA-2026-0042")
                .setMessage("Bonjour"));

        assertEquals("accepted", result.getStatus());
        assertEquals("0107019a-msg", result.getMessageId());
        TransportRequest r = stub.last();
        assertEquals("/v1/embed/facturx/email", r.uri().getPath());
        assertEquals("application/json", r.header("Accept"));
        assertTrue(r.header("Content-Type").startsWith("multipart/form-data; boundary="));
        String body = body(r);
        assertTrue(body.contains("name=\"pdf\"; filename=\"invoice.pdf\""));
        assertTrue(body.contains("name=\"xml\"; filename=\"invoice.xml\""));
        assertTrue(body.contains("name=\"email.from\"\r\n\r\nfactures@example.com\r\n"));
        assertTrue(body.contains("name=\"email.fromName\"\r\n\r\nFacturation\r\n"));
        assertTrue(body.contains("name=\"email.to\"\r\n\r\ncompta@example.org\r\n"));
        assertEquals(2, count(body, "name=\"email.cc\"\r\n\r\n"));
        assertTrue(body.contains("name=\"email.cc\"\r\n\r\nun@example.org\r\n"));
        assertTrue(body.contains("name=\"email.cc\"\r\n\r\ndeux@example.org\r\n"));
        assertEquals(1, count(body, "name=\"email.bcc\"\r\n\r\ntrois@example.org\r\n"));
        assertTrue(body.contains("name=\"email.replyTo\"\r\n\r\naide@example.com\r\n"));
        assertTrue(body.contains("name=\"email.subject\"\r\n\r\nFacture FA-2026-0042\r\n"));
        assertTrue(body.contains("name=\"email.message\"\r\n\r\nBonjour\r\n"));
        assertFalse(body.contains("skipValidation"));
        assertFalse(body.contains("name=\"rules\""));
    }

    @Test
    void emailEmbeddedFacturXOmitsUnsetFields(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("facture.pdf");
        Path xml = dir.resolve("facture.xml");
        Files.write(pdf, PDF);
        Files.write(xml, XML.getBytes(StandardCharsets.UTF_8));
        stub.respondJson(200, ACCEPTED);

        client().emailEmbeddedFacturX(pdf, xml, email());

        String body = body(stub.last());
        assertTrue(body.contains("filename=\"facture.pdf\""));
        assertTrue(body.contains("filename=\"facture.xml\""));
        assertEquals(2, count(body, "name=\"email."));
        assertTrue(body.contains("name=\"email.from\""));
        assertTrue(body.contains("name=\"email.to\""));
    }

    @Test
    void emailEmbeddedFacturXSendsRuleSets() {
        stub.respondJson(200, ACCEPTED);

        client().emailEmbeddedFacturX(FileUpload.ofPdf(PDF), FileUpload.ofXml(XML), email(),
                new ValidateOptions().addRule(RuleSet.BR_FR));

        assertEquals(1, count(body(stub.last()), "name=\"rules\"\r\n\r\nbr-fr\r\n"));
    }

    @Test
    void emailRequiresAnEmailMessage() {
        assertThrows(NullPointerException.class, () -> client().emailFacturX(Fixtures.invoice(), null));
        assertThrows(NullPointerException.class,
                () -> client().emailEmbeddedFacturX(FileUpload.ofPdf(PDF), FileUpload.ofXml(XML), null));
        assertTrue(stub.requests.isEmpty());
    }

    @Test
    void unverifiedSenderDomainSurfacesItsErrorCode() {
        stub.respondJson(400, "{\"title\":\"Sending domain not verified\",\"detail\":\"The sending domain example.com is not verified yet.\",\"errorCode\":4022,\"traceId\":\"00-abc-def-01\"}");

        InvoiceXmlApiException e = assertThrows(InvoiceXmlApiException.class,
                () -> client().emailFacturX(Fixtures.invoice(), email()));

        assertFalse(e instanceof InvoiceValidationException);
        assertEquals(400, e.getStatusCode());
        assertEquals(ErrorCodes.EMAIL_DOMAIN_NOT_VERIFIED, e.getErrorCode());
        assertEquals("00-abc-def-01", e.getTraceId());
        assertEquals(1, stub.requests.size());
    }

    @Test
    void emailPlanAndAccountRefusalsSurfaceTheirErrorCodes() {
        stub.respondJson(403, "{\"title\":\"Email sending not included in your plan\",\"errorCode\":4020}")
            .respondJson(403, "{\"title\":\"Email sending disabled\",\"errorCode\":4021}");

        InvoiceXmlApiException plan = assertThrows(InvoiceXmlApiException.class,
                () -> client().emailFacturX(Fixtures.invoice(), email()));
        InvoiceXmlApiException disabled = assertThrows(InvoiceXmlApiException.class,
                () -> client().emailEmbeddedFacturX(FileUpload.ofPdf(PDF), FileUpload.ofXml(XML), email()));

        assertEquals(ErrorCodes.EMAIL_NOT_IN_PLAN, plan.getErrorCode());
        assertEquals(ErrorCodes.EMAIL_SENDING_DISABLED, disabled.getErrorCode());
    }

    @Test
    void rejectedEmailFieldCarriesFindings() {
        stub.respondJson(400, "{\"title\":\"Validation failed\",\"errorCode\":4002,\"errors\":[{\"message\":\"The sender address is not valid.\",\"fields\":[\"email.from\"]}]}");

        InvoiceValidationException e = assertThrows(InvoiceValidationException.class,
                () -> client().emailFacturX(Fixtures.invoice(), email()));

        assertEquals(ErrorCodes.MODEL_VALIDATION, e.getErrorCode());
        assertEquals(List.of("email.from"), e.getErrors().get(0).getFields());
    }

    @Test
    void emailIsRetriedOnRateLimitWithTheSameBody() {
        stub.respondJson(429, "{\"title\":\"Too many requests\",\"errorCode\":4019}", "Retry-After", "2")
            .respondJson(200, ACCEPTED);

        EmailSendResult result = client().emailFacturX(Fixtures.invoice(), email());

        assertEquals("0107019a-msg", result.getMessageId());
        assertEquals(List.of(Duration.ofSeconds(2)), sleeps);
        assertEquals(2, stub.requests.size());
        assertArrayEquals(stub.requests.get(0).body(), stub.requests.get(1).body());
    }

    @Test
    void emailIsNotRetriedOnServerOrNetworkFailure() {
        stub.respondJson(503, "{\"title\":\"Service unavailable\"}");
        assertThrows(InvoiceXmlApiException.class, () -> client().emailFacturX(Fixtures.invoice(), email()));
        assertEquals(1, stub.requests.size());

        stub.fail(new IOException("connection reset"));
        assertThrows(InvoiceXmlConnectionException.class,
                () -> client().emailEmbeddedFacturX(FileUpload.ofPdf(PDF), FileUpload.ofXml(XML), email()));
        assertEquals(2, stub.requests.size());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void blankCopyAddressesAreDroppedOnBothRouteTypes() throws Exception {
        stub.respondJson(200, ACCEPTED).respondJson(200, ACCEPTED);
        EmailMessage email = email().setCc(List.of(" ", " one@example.org ")).setBcc(List.of(""));

        client().emailFacturX(Fixtures.invoice(), email);
        client().emailEmbeddedFacturX(FileUpload.ofPdf(PDF), FileUpload.ofXml(XML), email);

        JsonNode sent = json.readTree(stub.requests.get(0).body()).get("email");
        assertEquals(1, sent.get("cc").size());
        assertEquals("one@example.org", sent.at("/cc/0").asText());
        assertFalse(sent.has("bcc"), "a list left empty must be omitted");
        String form = body(stub.requests.get(1));
        assertEquals(1, count(form, "name=\"email.cc\""));
        assertTrue(form.contains("name=\"email.cc\"\r\n\r\none@example.org\r\n"));
        assertFalse(form.contains("name=\"email.bcc\""));
    }

    // ------------------------------------------------------------- render

    @Test
    void renderXmlPostsToItsRouteAndReturnsPdf(@TempDir Path dir) throws Exception {
        Path xml = dir.resolve("factur-x.xml");
        Files.write(xml, XML.getBytes(StandardCharsets.UTF_8));
        stub.respond(200, "application/pdf", PDF);

        assertArrayEquals(PDF, client().renderXmlToPdf(xml));

        assertEquals("/v1/render/xml/to/pdf", stub.last().uri().getPath());
        String body = body(stub.last());
        assertTrue(body.contains("name=\"file\"; filename=\"factur-x.xml\""));
        assertEquals(1, count(body, "form-data; name="), "unset options must be omitted");
    }

    @Test
    void renderXmlSendsOptions() {
        stub.respond(200, "application/pdf", PDF);

        client().renderXmlToPdf(FileUpload.ofXml(XML), new RenderOptions()
                .setLanguage(PdfLanguage.FR)
                .setLogoUrl("https://example.com/logo.png")
                .setIncludeQrCode(true));

        String body = body(stub.last());
        assertTrue(body.contains("name=\"language\"\r\n\r\nfr\r\n"));
        assertTrue(body.contains("name=\"logoUrl\"\r\n\r\nhttps://example.com/logo.png\r\n"));
        assertTrue(body.contains("name=\"includeQrCode\"\r\n\r\ntrue\r\n"));
    }

    @Test
    void renderSendsQrCodeOnlyWhenSet() {
        stub.respond(200, "application/pdf", PDF).respond(200, "application/pdf", PDF);

        client().renderXmlToPdf(FileUpload.ofXml(XML), new RenderOptions().setIncludeQrCode(false));
        client().renderXmlToPdf(FileUpload.ofXml(XML), new RenderOptions());

        assertTrue(body(stub.requests.get(0)).contains("name=\"includeQrCode\"\r\n\r\nfalse\r\n"));
        assertFalse(body(stub.requests.get(1)).contains("includeQrCode"));
    }

    // ------------------------------------------------------------ convert

    private void assertConvertsToXml(String route, Supplier<String> call) {
        stub.respond(200, "application/xml", XML.getBytes(StandardCharsets.UTF_8));

        assertEquals(XML, call.get());

        assertEquals(route, stub.last().uri().getPath());
        String body = body(stub.last());
        assertTrue(body.contains("form-data; name=\"file\"; filename="));
        assertEquals(1, count(body, "form-data; name="), "unset options must be omitted");
    }

    private void assertConvertsToPdf(String route, Supplier<byte[]> call) {
        stub.respond(200, "application/pdf", PDF);

        assertArrayEquals(PDF, call.get());

        assertEquals(route, stub.last().uri().getPath());
        String body = body(stub.last());
        assertTrue(body.contains("form-data; name=\"file\"; filename="));
        assertEquals(1, count(body, "form-data; name="), "unset options must be omitted");
    }

    @Test
    void convertRoutesToXmlPostTheFileAndReturnXml() {
        FacturXClient c = client();
        FileUpload pdf = FileUpload.ofPdf(PDF);

        assertConvertsToXml("/v1/convert/facturx/to/ubl", () -> c.convertFacturXToUbl(pdf));
        assertConvertsToXml("/v1/convert/facturx/to/cii", () -> c.convertFacturXToCii(pdf));
    }

    @Test
    void convertRoutesToFacturXPostTheFileAndReturnPdf() {
        FacturXClient c = client();
        FileUpload xml = FileUpload.ofXml(XML);

        assertConvertsToPdf("/v1/convert/cii/to/facturx", () -> c.convertCiiToFacturX(xml));
        assertConvertsToPdf("/v1/convert/ubl/to/facturx", () -> c.convertUblToFacturX(xml));
        assertConvertsToPdf("/v1/convert/xrechnung/to/facturx", () -> c.convertXRechnungToFacturX(xml));
    }

    @Test
    void convertTakesPaths(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("facture.pdf");
        Path xml = dir.resolve("facture.xml");
        Files.write(pdf, PDF);
        Files.write(xml, XML.getBytes(StandardCharsets.UTF_8));
        FacturXClient c = client();

        assertConvertsToXml("/v1/convert/facturx/to/cii", () -> c.convertFacturXToCii(pdf));
        assertTrue(body(stub.last()).contains("filename=\"facture.pdf\""));
        assertConvertsToPdf("/v1/convert/xrechnung/to/facturx", () -> c.convertXRechnungToFacturX(xml));
        assertTrue(body(stub.last()).contains("filename=\"facture.xml\""));
    }

    @Test
    void convertToXmlSendsValidationLanguageAndNoLanguage() {
        stub.respond(200, "application/xml", XML.getBytes(StandardCharsets.UTF_8));

        client().convertFacturXToUbl(FileUpload.ofPdf(PDF), new ConvertOptions().setValidationLanguage(PdfLanguage.FR));

        String body = body(stub.last());
        assertTrue(body.contains("name=\"validationLanguage\"\r\n\r\nfr\r\n"));
        assertFalse(body.contains("name=\"language\""));
    }

    @Test
    void convertToFacturXSendsLanguageAndValidationLanguage() {
        stub.respond(200, "application/pdf", PDF);

        client().convertCiiToFacturX(FileUpload.ofXml(XML), new ConvertToPdfOptions()
                .setLanguage(PdfLanguage.FR)
                .setValidationLanguage(PdfLanguage.DE));

        String body = body(stub.last());
        assertTrue(body.contains("name=\"language\"\r\n\r\nfr\r\n"));
        assertTrue(body.contains("name=\"validationLanguage\"\r\n\r\nde\r\n"));
    }

    @Test
    void convertRejectionCarriesFindings() {
        stub.respondJson(400, Fixtures.resource("create-400.json"));

        InvoiceValidationException e = assertThrows(InvoiceValidationException.class,
                () -> client().convertUblToFacturX(FileUpload.ofXml(XML)));

        assertEquals(ErrorCodes.MODEL_VALIDATION, e.getErrorCode());
        assertEquals("BR-CO-26", e.getErrors().get(0).getRule());
    }

    @Test
    void convertOfAPdfWithoutXmlSurfacesItsErrorCode() {
        stub.respondJson(400, "{\"title\":\"No embedded XML\",\"errorCode\":4006}");

        InvoiceXmlApiException e = assertThrows(InvoiceXmlApiException.class,
                () -> client().convertFacturXToUbl(FileUpload.ofPdf(PDF)));

        assertEquals(ErrorCodes.NO_EMBEDDED_XML, e.getErrorCode());
    }

    // ------------------------------------------------------------- report

    @Test
    void validateFacturXReportReturnsPdfAndPassedVerdict(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("facture.pdf");
        Files.write(pdf, PDF);
        stub.respond(200, "application/pdf", PDF, "X-Invoice-Valid", "true");

        ValidationReport report = client().validateFacturXReport(pdf);

        assertArrayEquals(PDF, report.getFile());
        assertEquals(Boolean.TRUE, report.getValid());
        assertEquals("/v1/validate/facturx/report", stub.last().uri().getPath());
        String body = body(stub.last());
        assertTrue(body.contains("name=\"file\"; filename=\"facture.pdf\""));
        assertEquals(1, count(body, "form-data; name="), "unset options must be omitted");
    }

    @Test
    void validateFacturXReportReturnsFailedVerdictWithoutThrowing() {
        stub.respond(200, "application/pdf", PDF, "X-Invoice-Valid", "false");

        ValidationReport report = client().validateFacturXReport(FileUpload.ofPdf(PDF));

        assertArrayEquals(PDF, report.getFile());
        assertEquals(Boolean.FALSE, report.getValid());
    }

    @Test
    void reportVerdictIsNullWhenTheHeaderIsMissingOrUnreadable() {
        stub.respond(200, "application/pdf", PDF).respond(200, "application/pdf", PDF, "X-Invoice-Valid", "maybe");

        assertNull(client().validateFacturXReport(FileUpload.ofPdf(PDF)).getValid());
        assertNull(client().validateFacturXReport(FileUpload.ofPdf(PDF)).getValid());
    }

    @Test
    void reportHeaderNameIsMatchedCaseInsensitively() {
        stub.respond(200, "application/pdf", PDF, "x-invoice-valid", "true");

        assertEquals(Boolean.TRUE, client().validateFacturXReport(FileUpload.ofPdf(PDF)).getValid());
    }

    @Test
    void reportSendsOnePartPerRuleAndTheValidationLanguage() {
        stub.respond(200, "application/pdf", PDF, "X-Invoice-Valid", "true");

        client().validateFacturXReport(FileUpload.ofPdf(PDF), new ReportOptions()
                .addRule(RuleSet.BR_FR)
                .addRule(RuleSet.BR_FR)
                .setValidationLanguage(PdfLanguage.FR));

        String body = body(stub.last());
        assertEquals(2, count(body, "name=\"rules\"\r\n\r\nbr-fr\r\n"));
        assertTrue(body.contains("name=\"validationLanguage\"\r\n\r\nfr\r\n"));
    }

    @Test
    void reportTransportProblemRaisesTheUsualException() {
        stub.respondJson(400, "{\"title\":\"Missing file\",\"errorCode\":4003}");

        InvoiceXmlApiException e = assertThrows(InvoiceXmlApiException.class,
                () -> client().validateFacturXReport(FileUpload.ofPdf(new byte[0])));

        assertEquals(ErrorCodes.MISSING_FILE, e.getErrorCode());
    }

    // ---------------------------------------------------------- transform

    @Test
    void transformToFacturXOmitsUnsetOptions(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("invoice.pdf");
        Files.write(pdf, PDF);
        stub.respond(200, "application/pdf", PDF).respond(200, "application/pdf", PDF);

        assertArrayEquals(PDF, client().transformToFacturX(pdf));
        client().transformToFacturX(FileUpload.ofPdf(PDF), new TransformOptions());

        assertEquals("/v1/transform/to/facturx", stub.last().uri().getPath());
        for (TransportRequest r : stub.requests) {
            assertEquals(1, count(body(r), "form-data; name="), "unset options must be omitted");
        }
    }

    @Test
    void transformToFacturXSendsEveryOption() {
        stub.respond(200, "application/pdf", PDF);

        client().transformToFacturX(FileUpload.ofPdf(PDF), new TransformOptions()
                .setLanguage(PdfLanguage.FR)
                .setPreserveOriginalPdf(true)
                .setIncludeAdvancedProperties(true)
                .setValidationLanguage(PdfLanguage.DE));

        String body = body(stub.last());
        assertTrue(body.contains("name=\"language\"\r\n\r\nfr\r\n"));
        assertTrue(body.contains("name=\"preserveOriginalPdf\"\r\n\r\ntrue\r\n"));
        assertTrue(body.contains("name=\"includeAdvancedProperties\"\r\n\r\ntrue\r\n"));
        assertTrue(body.contains("name=\"validationLanguage\"\r\n\r\nde\r\n"));
    }

    @Test
    void transformCanTurnOffTheDetailsSection() {
        stub.respond(200, "application/pdf", PDF);

        client().transformToFacturX(FileUpload.ofPdf(PDF), new TransformOptions().setIncludeAdvancedProperties(false));

        String body = body(stub.last());
        assertTrue(body.contains("name=\"includeAdvancedProperties\"\r\n\r\nfalse\r\n"));
        assertFalse(body.contains("preserveOriginalPdf"));
    }

    @Test
    void transformRateLimitMakesOneRequestAndThrows() {
        stub.respondJson(429, "{\"title\":\"Too many requests\",\"errorCode\":4019}", "Retry-After", "2");

        RateLimitException e = assertThrows(RateLimitException.class,
                () -> client().transformToFacturX(FileUpload.ofPdf(PDF)));

        assertEquals(1, stub.requests.size());
        assertEquals("/v1/transform/to/facturx", stub.last().uri().getPath());
        assertTrue(sleeps.isEmpty());
        assertEquals(Duration.ofSeconds(2), e.getRetryAfter());
        assertEquals(ErrorCodes.TOO_MANY_REQUESTS, e.getErrorCode());
    }

    @Test
    void transformRejectionCarriesFindings() {
        stub.respondJson(400, Fixtures.resource("create-400.json"));

        InvoiceValidationException e = assertThrows(InvoiceValidationException.class,
                () -> client().transformToFacturX(FileUpload.ofPdf(PDF)));

        assertEquals(ErrorCodes.MODEL_VALIDATION, e.getErrorCode());
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

    // -------------------------------------------------------------- parse

    private String parseResponse(String extra) throws Exception {
        String invoice = json.readTree(Fixtures.resource("extract-json.json")).get("invoice").toString();
        return "{\"invoice\":" + invoice + extra + "}";
    }

    @Test
    void parseJsonUploadsThePdfAndMapsConfidenceAndLanguage() throws Exception {
        stub.respondJson(200, parseResponse(",\"confidence\":{\"overall\":0.91,\"areas\":{"
                + "\"sellerIdentification\":0.95,\"buyerIdentification\":0.90,"
                + "\"taxCalculation\":0.98,\"lineItems\":null}},\"language\":\"fr\""));

        ParsedInvoice parsed = client().parseJson(FileUpload.ofPdf(PDF));

        TransportRequest r = stub.last();
        assertEquals("https://api.invoicexml.com/v1/parse/json", r.uri().toString());
        assertEquals("application/json", r.header("Accept"));
        assertTrue(r.header("Content-Type").startsWith("multipart/form-data"));
        assertTrue(body(r).contains("name=\"file\"; filename=\"invoice.pdf\""));
        assertTrue(body(r).contains("Content-Type: application/pdf"));

        assertEquals("FA-2026-0042", parsed.getInvoice().getInvoiceNumber());
        assertEquals(LocalDate.of(2026, 9, 1), parsed.getInvoice().getIssueDate());
        assertEquals(0, new BigDecimal("3541.50").compareTo(parsed.getInvoice().getTotals().getGrandTotalAmount()));
        assertEquals("fr", parsed.getLanguage());
        assertEquals(new BigDecimal("0.91"), parsed.getConfidence().getOverall());
        assertEquals(new BigDecimal("0.95"), parsed.getConfidence().getAreas().getSellerIdentification());
        assertEquals(new BigDecimal("0.90"), parsed.getConfidence().getAreas().getBuyerIdentification());
        assertEquals(new BigDecimal("0.98"), parsed.getConfidence().getAreas().getTaxCalculation());
        assertNull(parsed.getConfidence().getAreas().getLineItems());
    }

    @Test
    void parseJsonWithoutConfidenceOrLanguage(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("scan.pdf");
        Files.write(file, PDF);
        stub.respondJson(200, parseResponse(""));

        ParsedInvoice parsed = client().parseJson(file);

        assertTrue(body(stub.last()).contains("filename=\"scan.pdf\""));
        assertEquals("FA-2026-0042", parsed.getInvoice().getInvoiceNumber());
        assertNull(parsed.getConfidence());
        assertNull(parsed.getLanguage());
    }

    @Test
    void parseJsonRateLimitMakesOneRequestAndThrows() {
        stub.respondJson(429, "{\"title\":\"Too many requests\",\"errorCode\":4019}", "Retry-After", "2");

        RateLimitException e = assertThrows(RateLimitException.class,
                () -> client().parseJson(FileUpload.ofPdf(PDF)));

        assertEquals(1, stub.requests.size());
        assertEquals("/v1/parse/json", stub.last().uri().getPath());
        assertTrue(sleeps.isEmpty());
        assertEquals(Duration.ofSeconds(2), e.getRetryAfter());
        assertEquals(ErrorCodes.TOO_MANY_REQUESTS, e.getErrorCode());
    }

    @Test
    void parseJsonRefusalMapsToApiException() {
        stub.respondJson(400, "{\"title\":\"Not an invoice\",\"errorCode\":4008}");

        InvoiceXmlApiException e = assertThrows(InvoiceXmlApiException.class,
                () -> client().parseJson(FileUpload.ofPdf(PDF)));

        assertEquals(400, e.getStatusCode());
        assertEquals(ErrorCodes.NOT_AN_INVOICE, e.getErrorCode());
    }

    @Test
    void parseJsonRequiresAFile() {
        assertThrows(NullPointerException.class, () -> client().parseJson((Path) null));
        assertThrows(NullPointerException.class, () -> client().parseJson((FileUpload) null));
        assertTrue(stub.requests.isEmpty());
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
    void renderConvertAndReportRetryAfterRateLimit() {
        stub.respondJson(429, "{}", "Retry-After", "1").respond(200, "application/pdf", PDF);
        stub.respondJson(429, "{}", "Retry-After", "1").respond(200, "application/xml", XML.getBytes(StandardCharsets.UTF_8));
        stub.respondJson(429, "{}", "Retry-After", "1").respond(200, "application/pdf", PDF, "X-Invoice-Valid", "true");

        client().renderXmlToPdf(FileUpload.ofXml(XML));
        client().convertFacturXToUbl(FileUpload.ofPdf(PDF));
        client().validateFacturXReport(FileUpload.ofPdf(PDF));

        assertEquals(List.of(
                "/v1/render/xml/to/pdf", "/v1/render/xml/to/pdf",
                "/v1/convert/facturx/to/ubl", "/v1/convert/facturx/to/ubl",
                "/v1/validate/facturx/report", "/v1/validate/facturx/report"),
            stub.requests.stream().map(request -> request.uri().getPath()).collect(Collectors.toList()));
        assertEquals(3, sleeps.size());
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
