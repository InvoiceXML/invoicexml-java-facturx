package com.invoicexml.facturx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicexml.facturx.email.EmailMessage;
import com.invoicexml.facturx.email.EmailSendResult;
import com.invoicexml.facturx.model.CreateInvoiceRequest;
import com.invoicexml.facturx.model.InvoiceDocument;
import com.invoicexml.facturx.model.InvoiceOptions;
import com.invoicexml.facturx.model.PdfLanguage;
import com.invoicexml.facturx.model.RuleSet;
import com.invoicexml.facturx.model.UblProfile;
import com.invoicexml.facturx.transport.HttpTransport;
import com.invoicexml.facturx.transport.JdkHttpTransport;
import com.invoicexml.facturx.transport.TransportRequest;
import com.invoicexml.facturx.transport.TransportResponse;
import com.invoicexml.facturx.validation.ValidationReport;
import com.invoicexml.facturx.validation.ValidationResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Client for the Factur-X endpoints of the InvoiceXML API.
 *
 * <pre>{@code
 * FacturXClient client = FacturXClient.create(System.getenv("INVOICEXML_API_KEY"));
 *
 * byte[] pdf = client.createFacturX(invoice);              // Factur-X PDF/A-3
 * ValidationResult result = client.validateFacturX(Path.of("facture.pdf"));
 * }</pre>
 *
 * <p>Every create call validates the invoice against the full rule set before
 * the document is returned. A rejected invoice raises an
 * {@link InvoiceValidationException} listing the violated rules. For invoices
 * under the French mandate, add {@link RuleSet#BR_FR} to apply the BR-FR rules too.
 *
 * <p>HTTP 429 responses are retried automatically after the {@code Retry-After}
 * delay, except on transform calls. Other errors raise an {@link InvoiceXmlApiException} subclass, and
 * network failures raise an {@link InvoiceXmlConnectionException}. All are unchecked.
 *
 * <p>Instances are immutable and thread-safe. Create one and share it.
 */
public final class FacturXClient {

    /** The production API endpoint. */
    public static final String DEFAULT_BASE_URL = "https://api.invoicexml.com";

    /** This library's version. */
    public static final String VERSION = loadVersion();

    private final String apiKey;
    private final String baseUrl;
    private final HttpTransport transport;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration maxRetryWait;
    private final Sleeper sleeper;
    private final Clock clock;
    private final String userAgent;
    private final ObjectMapper mapper = Json.newMapper();

    private FacturXClient(Builder b) {
        this.apiKey = b.apiKey;
        this.baseUrl = b.baseUrl;
        this.transport = b.transport != null ? b.transport : new JdkHttpTransport(b.connectTimeout);
        this.timeout = b.timeout;
        this.maxRetries = b.maxRetries;
        this.maxRetryWait = b.maxRetryWait;
        this.sleeper = b.sleeper;
        this.clock = b.clock;
        this.userAgent = "invoicexml-java-facturx/" + VERSION
                + " (Java " + System.getProperty("java.version", "unknown") + ")";
    }

    /**
     * Creates a client for the production API with default settings.
     *
     * @param apiKey your InvoiceXML API key
     * @return the client
     */
    public static FacturXClient create(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    /**
     * Returns a builder for a client with custom settings.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------- create

    /**
     * Creates a Factur-X hybrid invoice: a PDF/A-3 with the CII XML embedded as
     * {@code factur-x.xml}.
     *
     * <p>The profile is EN 16931 unless the invoice's {@code specificationId}
     * declares another official profile (see {@link FacturXProfiles}). For the
     * MINIMUM and BASIC WL profiles the XML carries the header data only, while
     * the PDF shows the full lines, so lines remain required input. Totals and
     * the VAT breakdown are calculated from the lines when omitted.
     *
     * @param invoice the invoice
     * @return the PDF bytes
     * @throws InvoiceValidationException when the invoice violates the rule set
     */
    public byte[] createFacturX(InvoiceDocument invoice) {
        return createFacturX(new CreateInvoiceRequest().setInvoice(invoice));
    }

    /**
     * Creates a Factur-X hybrid invoice with options: extra rule sets such as
     * {@link RuleSet#BR_FR}, PDF language, logo, brand colour, or your own PDF as
     * the visual layer.
     *
     * @param invoice the invoice
     * @param options the options, or {@code null} for the defaults
     * @return the PDF bytes
     * @throws InvoiceValidationException when the invoice violates the rule set
     * @see #createFacturX(InvoiceDocument)
     */
    public byte[] createFacturX(InvoiceDocument invoice, InvoiceOptions options) {
        return createFacturX(new CreateInvoiceRequest().setInvoice(invoice).setOptions(options));
    }

    /**
     * Creates a Factur-X hybrid invoice from a full request envelope.
     *
     * @param request the request
     * @return the PDF bytes
     * @throws InvoiceValidationException when the invoice violates the rule set
     * @see #createFacturX(InvoiceDocument)
     */
    public byte[] createFacturX(CreateInvoiceRequest request) {
        return postJson("/v1/create/facturx", request, null).body();
    }

    /**
     * Creates a plain UN/CEFACT CII invoice XML, validated with the full rule set.
     *
     * <p>The XML carries the EN 16931 guideline identifier, so it embeds straight
     * into your own PDF with {@link #embedFacturX(FileUpload, FileUpload)}. Totals
     * and the VAT breakdown are calculated from the lines when omitted.
     *
     * @param invoice the invoice
     * @return the XML document
     * @throws InvoiceValidationException when the invoice violates the rule set
     */
    public String createCii(InvoiceDocument invoice) {
        return createCii(new CreateInvoiceRequest().setInvoice(invoice));
    }

    /**
     * Creates a plain CII invoice XML with options: extra rule sets such as
     * {@link RuleSet#BR_FR}, or the language of the default texts. The PDF
     * rendering options do not apply.
     *
     * @param invoice the invoice
     * @param options the options, or {@code null} for the defaults
     * @return the XML document
     * @throws InvoiceValidationException when the invoice violates the rule set
     * @see #createCii(InvoiceDocument)
     */
    public String createCii(InvoiceDocument invoice, InvoiceOptions options) {
        return createCii(new CreateInvoiceRequest().setInvoice(invoice).setOptions(options));
    }

    /**
     * Creates a plain CII invoice XML from a full request envelope.
     *
     * @param request the request
     * @return the XML document
     * @throws InvoiceValidationException when the invoice violates the rule set
     * @see #createCii(InvoiceDocument)
     */
    public String createCii(CreateInvoiceRequest request) {
        return new String(postJson("/v1/create/cii", request, null).body(), StandardCharsets.UTF_8);
    }

    /**
     * Creates a UBL 2.1 invoice XML, validated with the rule set of its profile.
     *
     * <p>The profile is Peppol BIS Billing 3.0 unless the options set another
     * {@link UblProfile}. Totals and the VAT breakdown are calculated from the
     * lines when omitted.
     *
     * @param invoice the invoice
     * @return the XML document
     * @throws InvoiceValidationException when the invoice violates the rule set
     */
    public String createUbl(InvoiceDocument invoice) {
        return createUbl(new CreateInvoiceRequest().setInvoice(invoice));
    }

    /**
     * Creates a UBL 2.1 invoice XML with options: the {@link UblProfile}, extra
     * rule sets such as {@link RuleSet#BR_FR}, or the language of the default
     * texts. The PDF rendering options do not apply.
     *
     * @param invoice the invoice
     * @param options the options, or {@code null} for the defaults
     * @return the XML document
     * @throws InvoiceValidationException when the invoice violates the rule set
     * @see #createUbl(InvoiceDocument)
     */
    public String createUbl(InvoiceDocument invoice, InvoiceOptions options) {
        return createUbl(new CreateInvoiceRequest().setInvoice(invoice).setOptions(options));
    }

    /**
     * Creates a UBL 2.1 invoice XML from a full request envelope.
     *
     * @param request the request
     * @return the XML document
     * @throws InvoiceValidationException when the invoice violates the rule set
     * @see #createUbl(InvoiceDocument)
     */
    public String createUbl(CreateInvoiceRequest request) {
        return new String(postJson("/v1/create/ubl", request, null).body(), StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------- validate

    /**
     * Validates a Factur-X PDF. The embedded XML is extracted, its profile
     * detected from the declared specification identifier, and the official XSD
     * and Schematron rules for that profile applied.
     *
     * @param pdf the PDF file
     * @return the result; check {@link ValidationResult#isValid()}
     */
    public ValidationResult validateFacturX(Path pdf) {
        return validateFacturX(FileUpload.of(pdf), null);
    }

    /**
     * Validates a Factur-X PDF upload.
     *
     * @param pdf the PDF upload
     * @return the result; check {@link ValidationResult#isValid()}
     * @see #validateFacturX(Path)
     */
    public ValidationResult validateFacturX(FileUpload pdf) {
        return validateFacturX(pdf, null);
    }

    /**
     * Validates a Factur-X PDF upload with options, for example the French
     * mandate's BR-FR rules on top of the profile rules.
     *
     * @param pdf the PDF upload
     * @param options the options, or {@code null} for the defaults
     * @return the result; check {@link ValidationResult#isValid()}
     * @see #validateFacturX(Path)
     */
    public ValidationResult validateFacturX(FileUpload pdf, ValidateOptions options) {
        Multipart form = new Multipart().file("file", pdf);
        addRules(form, options == null ? null : options.getRules());
        return postMultipartForJson("/v1/validate/facturx", form, ValidationResult.class);
    }

    /**
     * Validates a Factur-X PDF like {@link #validateFacturX(Path)} and returns a printable
     * PDF report with the verdict. An invalid invoice is a normal result, not an
     * exception, and so is a PDF without embedded XML, which gets a report saying so.
     *
     * @param pdf the PDF file
     * @return the report; check {@link ValidationReport#getValid()}
     */
    public ValidationReport validateFacturXReport(Path pdf) {
        return validateFacturXReport(FileUpload.of(pdf), null);
    }

    /**
     * Validates a Factur-X PDF upload and returns a printable PDF report with the verdict.
     *
     * @param pdf the PDF upload
     * @return the report; check {@link ValidationReport#getValid()}
     * @see #validateFacturXReport(Path)
     */
    public ValidationReport validateFacturXReport(FileUpload pdf) {
        return validateFacturXReport(pdf, null);
    }

    /**
     * Validates a Factur-X PDF upload and returns a printable PDF report with options,
     * for example the French mandate's BR-FR rules on top of the profile rules.
     *
     * @param pdf the PDF upload
     * @param options the options, or {@code null} for the defaults
     * @return the report; check {@link ValidationReport#getValid()}
     * @see #validateFacturXReport(Path)
     */
    public ValidationReport validateFacturXReport(FileUpload pdf, ReportOptions options) {
        Multipart form = new Multipart().file("file", pdf);
        if (options != null) {
            addRules(form, options.getRules());
            form.field("validationLanguage", language(options.getValidationLanguage()));
        }
        TransportResponse response = postMultipart("/v1/validate/facturx/report", form, null);
        return new ValidationReport(response.body(), verdict(response.header("X-Invoice-Valid")));
    }

    // ----------------------------------------------------------------- embed

    /**
     * Embeds a CII invoice XML into an existing PDF and returns a Factur-X PDF/A-3.
     * The XML is validated with the full rule set first.
     *
     * @param pdf the visual PDF
     * @param xml the CII XML
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the XML violates the rule set
     */
    public byte[] embedFacturX(Path pdf, Path xml) {
        return embedFacturX(FileUpload.of(pdf), FileUpload.of(xml), null);
    }

    /**
     * Embeds a CII invoice XML into an existing PDF.
     *
     * @param pdf the visual PDF
     * @param xml the CII XML
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the XML violates the rule set
     * @see #embedFacturX(Path, Path)
     */
    public byte[] embedFacturX(FileUpload pdf, FileUpload xml) {
        return embedFacturX(pdf, xml, null);
    }

    /**
     * Embeds a CII invoice XML into an existing PDF with options.
     *
     * @param pdf the visual PDF
     * @param xml the CII XML
     * @param options the options, or {@code null} for the defaults
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the XML violates the rule set
     * @see #embedFacturX(Path, Path)
     */
    public byte[] embedFacturX(FileUpload pdf, FileUpload xml, EmbedOptions options) {
        Multipart form = new Multipart().file("pdf", pdf).file("xml", xml);
        if (options != null) {
            if (options.isSkipValidation()) {
                form.field("skipValidation", "true");
            }
            addRules(form, options.getRules());
        }
        return postMultipart("/v1/embed/facturx", form, null).body();
    }

    // ----------------------------------------------------------------- email

    /**
     * Creates a Factur-X hybrid invoice like {@link #createFacturX(InvoiceDocument)}
     * and sends it as a real email from your own verified domain. The result is
     * the message id of that email, not the file.
     *
     * <p>The sender must be on a domain added and verified on the Verified email
     * domains page of the dashboard. A result means the email was handed over for
     * delivery, not that the recipient's server has received it yet.
     *
     * <p>Only HTTP 429 is retried, because nothing was sent. After an
     * {@link InvoiceXmlConnectionException} the email may or may not have gone out,
     * so check before you send it again.
     *
     * @param invoice the invoice
     * @param email the sender, recipient and optional texts
     * @return the status and message id of the sent email
     * @throws InvoiceValidationException when the invoice violates the rule set or the API rejects an email field
     * @throws InvoiceXmlApiException with {@link ErrorCodes#EMAIL_DOMAIN_NOT_VERIFIED},
     *         {@link ErrorCodes#EMAIL_NOT_IN_PLAN} or {@link ErrorCodes#EMAIL_SENDING_DISABLED}
     *         when the email cannot be sent
     */
    public EmailSendResult emailFacturX(InvoiceDocument invoice, EmailMessage email) {
        return emailFacturX(new CreateInvoiceRequest().setInvoice(invoice), email);
    }

    /**
     * Creates a Factur-X hybrid invoice with options and sends it as a real email from
     * your own verified domain. The result is the message id, not the file.
     *
     * @param invoice the invoice
     * @param email the sender, recipient and optional texts
     * @param options the options, or {@code null} for the defaults
     * @return the status and message id of the sent email
     * @throws InvoiceValidationException when the invoice violates the rule set or the API rejects an email field
     * @see #emailFacturX(InvoiceDocument, EmailMessage)
     */
    public EmailSendResult emailFacturX(InvoiceDocument invoice, EmailMessage email, InvoiceOptions options) {
        return emailFacturX(new CreateInvoiceRequest().setInvoice(invoice).setOptions(options), email);
    }

    /**
     * Creates a Factur-X hybrid invoice from a full request envelope and sends it as a
     * real email from your own verified domain. The result is the message id, not the file.
     *
     * @param request the request
     * @param email the sender, recipient and optional texts
     * @return the status and message id of the sent email
     * @throws InvoiceValidationException when the invoice violates the rule set or the API rejects an email field
     * @see #emailFacturX(InvoiceDocument, EmailMessage)
     */
    public EmailSendResult emailFacturX(CreateInvoiceRequest request, EmailMessage email) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(email, "email");
        String path = "/v1/create/facturx/email";
        return readJson(path, postJson(path, new EmailInvoiceRequest(request, wireEmail(email)), "application/json"),
                EmailSendResult.class);
    }

    /**
     * Embeds a CII invoice XML into an existing PDF like
     * {@link #embedFacturX(Path, Path)} and sends the Factur-X PDF/A-3 as a real email
     * from your own verified domain. The result is the message id, not the file. The
     * XML always runs through the full rule set first.
     *
     * @param pdf the visual PDF
     * @param xml the CII XML
     * @param email the sender, recipient and optional texts
     * @return the status and message id of the sent email
     * @throws InvoiceValidationException when the XML violates the rule set or the API rejects an email field
     * @see #emailFacturX(InvoiceDocument, EmailMessage)
     */
    public EmailSendResult emailEmbeddedFacturX(Path pdf, Path xml, EmailMessage email) {
        return emailEmbeddedFacturX(FileUpload.of(pdf), FileUpload.of(xml), email, null);
    }

    /**
     * Embeds a CII invoice XML into an existing PDF and sends the Factur-X PDF/A-3 as a
     * real email from your own verified domain. The result is the message id, not the file.
     *
     * @param pdf the visual PDF
     * @param xml the CII XML
     * @param email the sender, recipient and optional texts
     * @return the status and message id of the sent email
     * @throws InvoiceValidationException when the XML violates the rule set or the API rejects an email field
     * @see #emailEmbeddedFacturX(Path, Path, EmailMessage)
     */
    public EmailSendResult emailEmbeddedFacturX(FileUpload pdf, FileUpload xml, EmailMessage email) {
        return emailEmbeddedFacturX(pdf, xml, email, null);
    }

    /**
     * Embeds a CII invoice XML into an existing PDF with options, for example the
     * French mandate's BR-FR rules on top of the profile rules, and sends the Factur-X
     * PDF/A-3 as a real email from your own verified domain. The result is the message
     * id, not the file.
     *
     * @param pdf the visual PDF
     * @param xml the CII XML
     * @param email the sender, recipient and optional texts
     * @param options the options, or {@code null} for the defaults
     * @return the status and message id of the sent email
     * @throws InvoiceValidationException when the XML violates the rule set or the API rejects an email field
     * @see #emailEmbeddedFacturX(Path, Path, EmailMessage)
     */
    public EmailSendResult emailEmbeddedFacturX(FileUpload pdf, FileUpload xml, EmailMessage email,
            ValidateOptions options) {
        Objects.requireNonNull(email, "email");
        Multipart form = new Multipart().file("pdf", pdf).file("xml", xml);
        addRules(form, options == null ? null : options.getRules());
        addEmail(form, wireEmail(email));
        return postMultipartForJson("/v1/embed/facturx/email", form, EmailSendResult.class);
    }

    // ---------------------------------------------------------------- render

    /**
     * Renders a CII invoice XML, such as the XML of a Factur-X invoice, as a
     * human-readable PDF.
     *
     * @param xml the CII XML file
     * @return the PDF bytes
     */
    public byte[] renderCiiToPdf(Path xml) {
        return renderCiiToPdf(FileUpload.of(xml), null);
    }

    /**
     * Renders a CII invoice XML upload as a human-readable PDF.
     *
     * @param xml the CII XML upload
     * @return the PDF bytes
     * @see #renderCiiToPdf(Path)
     */
    public byte[] renderCiiToPdf(FileUpload xml) {
        return renderCiiToPdf(xml, null);
    }

    /**
     * Renders a CII invoice XML upload as a human-readable PDF with options.
     *
     * @param xml the CII XML upload
     * @param options the options, or {@code null} for the defaults
     * @return the PDF bytes
     * @see #renderCiiToPdf(Path)
     */
    public byte[] renderCiiToPdf(FileUpload xml, RenderOptions options) {
        Multipart form = new Multipart().file("file", xml);
        if (options != null) {
            form.field("language", language(options.getLanguage()));
            form.field("logoUrl", options.getLogoUrl());
            form.field("includeQrCode", flag(options.getIncludeQrCode()));
        }
        return postMultipart("/v1/render/cii/to/pdf", form, null).body();
    }

    // --------------------------------------------------------------- convert

    /**
     * Converts a Factur-X PDF to UBL 2.1 XML. The XML embedded in the PDF is read and
     * transcoded, and the result is validated before it is returned.
     *
     * @param pdf the Factur-X PDF
     * @return the XML document
     * @throws InvoiceValidationException when the result violates the rule set
     * @throws InvoiceXmlApiException with {@link ErrorCodes#NO_EMBEDDED_XML} when the PDF carries no XML
     */
    public String convertFacturXToUbl(Path pdf) {
        return convertFacturXToUbl(FileUpload.of(pdf), null);
    }

    /**
     * Converts a Factur-X PDF upload to UBL 2.1 XML.
     *
     * @param pdf the Factur-X PDF upload
     * @return the XML document
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #convertFacturXToUbl(Path)
     */
    public String convertFacturXToUbl(FileUpload pdf) {
        return convertFacturXToUbl(pdf, null);
    }

    /**
     * Converts a Factur-X PDF upload to UBL 2.1 XML with options.
     *
     * @param pdf the Factur-X PDF upload
     * @param options the options, or {@code null} for the defaults
     * @return the XML document
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #convertFacturXToUbl(Path)
     */
    public String convertFacturXToUbl(FileUpload pdf, ConvertOptions options) {
        return convertToXml("/v1/convert/facturx/to/ubl", pdf, options);
    }

    /**
     * Converts a Factur-X PDF to CII XML. The XML embedded in the PDF is read and
     * rebuilt, and the result is validated before it is returned.
     *
     * @param pdf the Factur-X PDF
     * @return the XML document
     * @throws InvoiceValidationException when the result violates the rule set
     * @throws InvoiceXmlApiException with {@link ErrorCodes#NO_EMBEDDED_XML} when the PDF carries no XML
     */
    public String convertFacturXToCii(Path pdf) {
        return convertFacturXToCii(FileUpload.of(pdf), null);
    }

    /**
     * Converts a Factur-X PDF upload to CII XML.
     *
     * @param pdf the Factur-X PDF upload
     * @return the XML document
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #convertFacturXToCii(Path)
     */
    public String convertFacturXToCii(FileUpload pdf) {
        return convertFacturXToCii(pdf, null);
    }

    /**
     * Converts a Factur-X PDF upload to CII XML with options.
     *
     * @param pdf the Factur-X PDF upload
     * @param options the options, or {@code null} for the defaults
     * @return the XML document
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #convertFacturXToCii(Path)
     */
    public String convertFacturXToCii(FileUpload pdf, ConvertOptions options) {
        return convertToXml("/v1/convert/facturx/to/cii", pdf, options);
    }

    /**
     * Converts CII XML to a Factur-X PDF/A-3: a PDF is rendered from the invoice and
     * the XML embedded into it, after validation with the full rule set.
     *
     * @param xml the CII XML
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     */
    public byte[] convertCiiToFacturX(Path xml) {
        return convertCiiToFacturX(FileUpload.of(xml), null);
    }

    /**
     * Converts a CII XML upload to a Factur-X PDF/A-3.
     *
     * @param xml the CII XML upload
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #convertCiiToFacturX(Path)
     */
    public byte[] convertCiiToFacturX(FileUpload xml) {
        return convertCiiToFacturX(xml, null);
    }

    /**
     * Converts a CII XML upload to a Factur-X PDF/A-3 with options.
     *
     * @param xml the CII XML upload
     * @param options the options, or {@code null} for the defaults
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #convertCiiToFacturX(Path)
     */
    public byte[] convertCiiToFacturX(FileUpload xml, ConvertToPdfOptions options) {
        return convertToPdf("/v1/convert/cii/to/facturx", xml, options);
    }

    /**
     * Converts UBL 2.1 XML to a Factur-X PDF/A-3: a PDF is rendered from the invoice
     * and the invoice embedded into it as CII XML, after validation with the full rule set.
     *
     * @param xml the UBL XML
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     */
    public byte[] convertUblToFacturX(Path xml) {
        return convertUblToFacturX(FileUpload.of(xml), null);
    }

    /**
     * Converts a UBL 2.1 XML upload to a Factur-X PDF/A-3.
     *
     * @param xml the UBL XML upload
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #convertUblToFacturX(Path)
     */
    public byte[] convertUblToFacturX(FileUpload xml) {
        return convertUblToFacturX(xml, null);
    }

    /**
     * Converts a UBL 2.1 XML upload to a Factur-X PDF/A-3 with options.
     *
     * @param xml the UBL XML upload
     * @param options the options, or {@code null} for the defaults
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #convertUblToFacturX(Path)
     */
    public byte[] convertUblToFacturX(FileUpload xml, ConvertToPdfOptions options) {
        return convertToPdf("/v1/convert/ubl/to/facturx", xml, options);
    }

    /**
     * Converts an XRechnung (UBL or CII) to a Factur-X PDF/A-3: a PDF is rendered from
     * the invoice and the invoice embedded into it as CII XML, after validation with
     * the full rule set.
     *
     * @param xml the XRechnung XML
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     */
    public byte[] convertXRechnungToFacturX(Path xml) {
        return convertXRechnungToFacturX(FileUpload.of(xml), null);
    }

    /**
     * Converts an XRechnung upload to a Factur-X PDF/A-3.
     *
     * @param xml the XRechnung XML upload
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #convertXRechnungToFacturX(Path)
     */
    public byte[] convertXRechnungToFacturX(FileUpload xml) {
        return convertXRechnungToFacturX(xml, null);
    }

    /**
     * Converts an XRechnung upload to a Factur-X PDF/A-3 with options.
     *
     * @param xml the XRechnung XML upload
     * @param options the options, or {@code null} for the defaults
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #convertXRechnungToFacturX(Path)
     */
    public byte[] convertXRechnungToFacturX(FileUpload xml, ConvertToPdfOptions options) {
        return convertToPdf("/v1/convert/xrechnung/to/facturx", xml, options);
    }

    // ------------------------------------------------------------- transform

    /**
     * Reads a PDF invoice and returns a Factur-X PDF/A-3. AI reads the PDF, so a person
     * must check the result before it is used.
     *
     * <p>By default the PDF is rendered fresh from the data read and ends with an
     * Electronic invoice details section, so the technical values can be checked on
     * the pages.
     *
     * <p>A 429 on this method is not retried automatically, so wait for
     * {@code Retry-After} before calling again if you want to retry.
     *
     * @param pdf the PDF invoice
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     */
    public byte[] transformToFacturX(Path pdf) {
        return transformToFacturX(FileUpload.of(pdf), null);
    }

    /**
     * Reads a PDF invoice upload and returns a Factur-X PDF/A-3. AI reads the PDF, so a
     * person must check the result before it is used.
     *
     * <p>A 429 on this method is not retried automatically, so wait for
     * {@code Retry-After} before calling again if you want to retry.
     *
     * @param pdf the PDF upload
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #transformToFacturX(Path)
     */
    public byte[] transformToFacturX(FileUpload pdf) {
        return transformToFacturX(pdf, null);
    }

    /**
     * Reads a PDF invoice upload and returns a Factur-X PDF/A-3 with options. AI reads
     * the PDF, so a person must check the result before it is used.
     *
     * <p>A 429 on this method is not retried automatically, so wait for
     * {@code Retry-After} before calling again if you want to retry.
     *
     * @param pdf the PDF upload
     * @param options the options, or {@code null} for the defaults
     * @return the hybrid PDF bytes
     * @throws InvoiceValidationException when the result violates the rule set
     * @see #transformToFacturX(Path)
     */
    public byte[] transformToFacturX(FileUpload pdf, TransformOptions options) {
        Multipart form = new Multipart().file("file", pdf);
        if (options != null) {
            form.field("language", language(options.getLanguage()));
            form.field("preserveOriginalPdf", flag(options.getPreserveOriginalPdf()));
            form.field("includeAdvancedProperties", flag(options.getIncludeAdvancedProperties()));
            form.field("validationLanguage", language(options.getValidationLanguage()));
        }
        return postMultipartOnce("/v1/transform/to/facturx", form).body();
    }

    // --------------------------------------------------------------- extract

    /**
     * Reads a Factur-X PDF or a CII XML into the structured invoice model.
     *
     * @param file the PDF or XML file
     * @return the invoice
     */
    public InvoiceDocument extractJson(Path file) {
        return extractJson(FileUpload.of(file));
    }

    /**
     * Reads a Factur-X PDF or a CII XML upload into the structured invoice model.
     *
     * @param file the PDF or XML upload
     * @return the invoice
     * @see #extractJson(Path)
     */
    public InvoiceDocument extractJson(FileUpload file) {
        ExtractJsonResponse response = postMultipartForJson("/v1/extract/json",
                new Multipart().file("file", file), ExtractJsonResponse.class);
        return response.invoice;
    }

    /**
     * Extracts the supporting documents embedded in an invoice, such as delivery
     * notes or timesheets, as a ZIP archive. Payloads are decoded and returned
     * verbatim. External URIs are not fetched.
     *
     * @param file the PDF or XML file
     * @return the ZIP bytes
     * @throws InvoiceXmlApiException with {@link ErrorCodes#NO_ATTACHMENTS} when the invoice carries none
     */
    public byte[] extractAttachments(Path file) {
        return extractAttachments(FileUpload.of(file));
    }

    /**
     * Extracts the supporting documents embedded in an invoice upload as a ZIP archive.
     *
     * @param file the PDF or XML upload
     * @return the ZIP bytes
     * @throws InvoiceXmlApiException with {@link ErrorCodes#NO_ATTACHMENTS} when the invoice carries none
     * @see #extractAttachments(Path)
     */
    public byte[] extractAttachments(FileUpload file) {
        return postMultipart("/v1/extract/attachments", new Multipart().file("file", file), null).body();
    }

    // -------------------------------------------------------------- plumbing

    private String convertToXml(String path, FileUpload file, ConvertOptions options) {
        Multipart form = new Multipart().file("file", file);
        if (options != null) {
            form.field("validationLanguage", language(options.getValidationLanguage()));
        }
        return new String(postMultipart(path, form, null).body(), StandardCharsets.UTF_8);
    }

    private byte[] convertToPdf(String path, FileUpload xml, ConvertToPdfOptions options) {
        Multipart form = new Multipart().file("file", xml);
        if (options != null) {
            form.field("language", language(options.getLanguage()));
            form.field("validationLanguage", language(options.getValidationLanguage()));
        }
        return postMultipart(path, form, null).body();
    }

    /** The verdict header's value, {@code null} when it is missing or says neither. */
    private static Boolean verdict(String header) {
        if ("true".equalsIgnoreCase(header)) {
            return Boolean.TRUE;
        }
        return "false".equalsIgnoreCase(header) ? Boolean.FALSE : null;
    }

    /** The form value of a language, {@code null} (not sent) when unset. */
    private static String language(PdfLanguage language) {
        return language == null ? null : language.name().toLowerCase(Locale.ROOT);
    }

    /** The form value of a switch, {@code null} (not sent) when unset. */
    private static String flag(Boolean value) {
        return value == null ? null : value.toString();
    }

    private static void addRules(Multipart form, List<RuleSet> rules) {
        if (rules == null) {
            return;
        }
        for (RuleSet rule : rules) {
            if (rule != null) {
                form.field("rules", rule.getValue());
            }
        }
    }

    /** The email as sent on every route: blank copy addresses dropped, the others trimmed. */
    private static EmailMessage wireEmail(EmailMessage email) {
        return new EmailMessage()
                .setFrom(email.getFrom())
                .setFromName(email.getFromName())
                .setTo(email.getTo())
                .setCc(copyAddresses(email.getCc()))
                .setBcc(copyAddresses(email.getBcc()))
                .setReplyTo(email.getReplyTo())
                .setSubject(email.getSubject())
                .setMessage(email.getMessage());
    }

    private static List<String> copyAddresses(List<String> addresses) {
        if (addresses == null) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String address : addresses) {
            if (address != null && !address.isBlank()) {
                kept.add(address.strip());
            }
        }
        return kept.isEmpty() ? null : kept;
    }

    private static void addEmail(Multipart form, EmailMessage email) {
        form.field("email.from", email.getFrom());
        form.field("email.fromName", email.getFromName());
        form.field("email.to", email.getTo());
        addEach(form, "email.cc", email.getCc());
        addEach(form, "email.bcc", email.getBcc());
        form.field("email.replyTo", email.getReplyTo());
        form.field("email.subject", email.getSubject());
        form.field("email.message", email.getMessage());
    }

    private static void addEach(Multipart form, String name, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            form.field(name, value);
        }
    }

    private TransportResponse postJson(String path, Object body, String accept) {
        Objects.requireNonNull(body, "request");
        byte[] json;
        try {
            json = mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new InvoiceXmlException("Cannot serialize the request: " + e.getOriginalMessage(), e);
        }
        return execute(path, "application/json", json, accept);
    }

    private TransportResponse postMultipart(String path, Multipart form, String accept) {
        return execute(path, form.contentType(), form.build(), accept);
    }

    private TransportResponse postMultipartOnce(String path, Multipart form) {
        return execute(path, form.contentType(), form.build(), null, 0);
    }

    private <T> T postMultipartForJson(String path, Multipart form, Class<T> type) {
        return readJson(path, postMultipart(path, form, "application/json"), type);
    }

    private <T> T readJson(String path, TransportResponse response, Class<T> type) {
        try {
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new InvoiceXmlException("Cannot read the response of " + path + ": " + e.getMessage(), e);
        }
    }

    private TransportResponse execute(String path, String contentType, byte[] body, String accept) {
        return execute(path, contentType, body, accept, maxRetries);
    }

    private TransportResponse execute(String path, String contentType, byte[] body, String accept, int retries) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", contentType);
        if (accept != null) {
            headers.put("Accept", accept);
        }
        headers.put("User-Agent", userAgent);
        TransportRequest request = new TransportRequest("POST", URI.create(baseUrl + path), headers, body, timeout);

        for (int attempt = 0; ; attempt++) {
            TransportResponse response;
            try {
                response = transport.send(request);
            } catch (IOException e) {
                throw new InvoiceXmlConnectionException("Request to " + path + " failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InvoiceXmlConnectionException("Interrupted while calling " + path, e);
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response;
            }
            if (status == 429 && attempt < retries) {
                Duration wait = RetryAfter.parse(response.header("Retry-After"), clock);
                if (wait == null) {
                    wait = Duration.ofSeconds(1L << Math.min(attempt, 5));
                }
                if (wait.compareTo(maxRetryWait) <= 0) {
                    try {
                        sleeper.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InvoiceXmlConnectionException("Interrupted while waiting to retry " + path, e);
                    }
                    continue;
                }
            }
            throw ErrorMapper.toException(response, mapper, clock);
        }
    }

    private static String loadVersion() {
        try (InputStream in = FacturXClient.class.getResourceAsStream("version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.startsWith("$")) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "dev";
    }

    /** Response envelope of {@code /v1/extract/json}. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ExtractJsonResponse {
        @com.fasterxml.jackson.annotation.JsonProperty("invoice")
        InvoiceDocument invoice;
    }

    /** Request body of the create email endpoint: the create envelope plus {@code email}. */
    private static final class EmailInvoiceRequest {
        @com.fasterxml.jackson.annotation.JsonUnwrapped
        final CreateInvoiceRequest request;

        @com.fasterxml.jackson.annotation.JsonProperty("email")
        final EmailMessage email;

        EmailInvoiceRequest(CreateInvoiceRequest request, EmailMessage email) {
            this.request = request;
            this.email = email;
        }
    }

    /**
     * Builder for {@link FacturXClient}.
     */
    public static final class Builder {

        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private HttpTransport transport;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration timeout = Duration.ofSeconds(120);
        private int maxRetries = 3;
        private Duration maxRetryWait = Duration.ofSeconds(60);
        private Sleeper sleeper = Sleeper.SYSTEM;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /**
         * Sets the API key, sent as {@code Authorization: Bearer <key>}. Required.
         *
         * @param apiKey the API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the API base URL. Defaults to {@value FacturXClient#DEFAULT_BASE_URL}.
         *
         * @param baseUrl the base URL, without the {@code /v1} path
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the TCP connect timeout of the default transport. Defaults to 10 seconds.
         * Ignored when a custom {@link #transport(HttpTransport)} is set.
         *
         * @param connectTimeout the timeout
         * @return this builder
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * Sets the overall timeout of one request. Defaults to 120 seconds.
         *
         * @param timeout the timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets how many times a request answered with HTTP 429 is retried after the
         * {@code Retry-After} delay, on every call except transform. Defaults to 3.
         * Use 0 to disable retries.
         *
         * @param maxRetries the number of retries
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the longest {@code Retry-After} delay the client waits out by itself.
         * A longer delay raises a {@link RateLimitException} immediately. Defaults to 60 seconds.
         *
         * @param maxRetryWait the longest delay
         * @return this builder
         */
        public Builder maxRetryWait(Duration maxRetryWait) {
            this.maxRetryWait = maxRetryWait;
            return this;
        }

        /**
         * Replaces the HTTP layer, for example to reuse a configured
         * {@link java.net.http.HttpClient} via {@link JdkHttpTransport#JdkHttpTransport(java.net.http.HttpClient)}.
         *
         * @param transport the transport
         * @return this builder
         */
        public Builder transport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        Builder sleeper(Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Builds the client.
         *
         * @return the client
         * @throws IllegalArgumentException when the API key is missing or a setting is invalid
         */
        public FacturXClient build() {
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new IllegalArgumentException("An API key is required. Get one at https://www.invoicexml.com/account/signup");
            }
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("baseUrl must not be empty");
            }
            baseUrl = baseUrl.trim();
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(maxRetryWait, "maxRetryWait");
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must not be negative");
            }
            return new FacturXClient(this);
        }
    }
}
