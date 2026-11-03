# InvoiceXML Factur-X client for Java

Create, validate, embed, render, convert and extract **Factur-X** e-invoices from Java with the [InvoiceXML API](https://www.invoicexml.com). Build the invoice from typed EN 16931 models and get back a compliant Factur-X PDF/A-3, already validated against the official rule set and, on request, the French mandate's BR-FR rules.

- Java 11 or later, `java.net.http.HttpClient`, Jackson as the only dependency
- Typed EN 16931 invoice model generated from the API's OpenAPI schema
- All Factur-X profiles, from MINIMUM to EXTENDED
- Rejected invoices surface the violated rules as typed findings
- Automatic retry on HTTP 429 on every call except transform, honouring `Retry-After`

## Installation

Maven:

```xml
<dependency>
    <groupId>com.invoicexml</groupId>
    <artifactId>invoicexml-facturx</artifactId>
    <version>0.4.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("com.invoicexml:invoicexml-facturx:0.4.0")
```

Gradle (Groovy DSL):

```groovy
implementation 'com.invoicexml:invoicexml-facturx:0.4.0'
```

## Get an API key

Sign up at [www.invoicexml.com/account/signup](https://www.invoicexml.com/account/signup) for a 30-day free trial with 100 credits. No credit card is needed.

## Create a Factur-X invoice

```java
var client = FacturXClient.create(System.getenv("INVOICEXML_API_KEY"));

var invoice = new InvoiceDocument()
    .setInvoiceNumber("FA-2026-0042").setIssueDate(LocalDate.of(2026, 9, 1)).setCurrency("EUR")
    .setSeller(new SellerParty().setName("Atelier Numerique SARL").setVatIdentifier("FR32123456789")
        .setLegalRegistration(SellerLegalRegistration.of("123456789", "0002"))
        .setPostalAddress(new SellerPostalAddress().setLine1("18 Rue de la Republique").setCity("Lyon").setPostCode("69002").setCountry("FR")))
    .setBuyer(new BuyerParty().setName("Librairie Grand Siecle SAS")
        .setPostalAddress(new BuyerPostalAddress().setLine1("5 Boulevard Saint-Germain").setCity("Paris").setPostCode("75005").setCountry("FR")))
    .setPaymentDetails(new PaymentDetails().setPaymentAccountIdentifier("FR7630006000011234567890189"))
    .addLine(new InvoiceLine().setQuantity(new BigDecimal("12")).setItem(new ItemInformation().setName("Maintenance applicative"))
        .setPriceDetails(new PriceDetails().setNetPrice(new BigDecimal("180.00"))).setVatInformation(new LineVatInformation().setRate(new BigDecimal("20"))));

Files.write(Path.of("facture-facturx.pdf"), client.createFacturX(invoice, new InvoiceOptions().setLanguage(PdfLanguage.FR)));
```

Totals and the VAT breakdown are calculated from the lines. The PDF is a Factur-X PDF/A-3 with the EN 16931 profile and the CII XML embedded as `factur-x.xml`.

## API surface

| Method | Endpoint | Returns |
|---|---|---|
| `createFacturX(invoice[, options])` | `POST /v1/create/facturx` | Factur-X PDF/A-3 (`byte[]`) |
| `createCii(invoice[, options])` | [`POST /v1/create/cii`](https://www.invoicexml.com/docs/api/create/cii) | CII XML (`String`) |
| `createUbl(invoice[, options])` | [`POST /v1/create/ubl`](https://www.invoicexml.com/docs/api/create/ubl) | UBL XML (`String`) |
| `validateFacturX(pdf[, options])` | `POST /v1/validate/facturx` | `ValidationResult` |
| `validateFacturXReport(pdf[, options])` | `POST /v1/validate/facturx/report` | `ValidationReport` |
| `embedFacturX(pdf, xml[, options])` | `POST /v1/embed/facturx` | Factur-X PDF/A-3 (`byte[]`) |
| `emailFacturX(invoice, email[, options])` | `POST /v1/create/facturx/email` | `EmailSendResult` |
| `emailEmbeddedFacturX(pdf, xml, email[, options])` | `POST /v1/embed/facturx/email` | `EmailSendResult` |
| `renderCiiToPdf(xml[, options])` | `POST /v1/render/cii/to/pdf` | Readable PDF (`byte[]`) |
| `convertFacturXToUbl(pdf[, options])` | `POST /v1/convert/facturx/to/ubl` | UBL XML (`String`) |
| `convertFacturXToCii(pdf[, options])` | `POST /v1/convert/facturx/to/cii` | CII XML (`String`) |
| `convertCiiToFacturX(xml[, options])` | `POST /v1/convert/cii/to/facturx` | Factur-X PDF/A-3 (`byte[]`) |
| `convertUblToFacturX(xml[, options])` | `POST /v1/convert/ubl/to/facturx` | Factur-X PDF/A-3 (`byte[]`) |
| `convertXRechnungToFacturX(xml[, options])` | `POST /v1/convert/xrechnung/to/facturx` | Factur-X PDF/A-3 (`byte[]`) |
| `transformToFacturX(pdf[, options])` | `POST /v1/transform/to/facturx` | Factur-X PDF/A-3 (`byte[]`), check before use |
| `extractJson(file)` | `POST /v1/extract/json` | `InvoiceDocument` |
| `extractAttachments(file)` | `POST /v1/extract/attachments` | ZIP of embedded documents (`byte[]`) |

File parameters accept a `Path` or a `FileUpload`. `FileUpload.ofPdf(bytes)` and `FileUpload.ofXml(string)` cover in-memory documents.

## The French e-invoicing mandate

Receiving e-invoices is mandatory for every business in France from September 2026, and issuing follows in phases. Add the `BR_FR` rule set to check an invoice against the mandate's BR-FR rules on top of its profile. It works on create, validate and embed, and needs at least the BASIC profile:

```java
byte[] pdf = client.createFacturX(invoice
        .setBusinessProcessType("S1")                                       // billing mode, here services
        .addNote(InvoiceNote.of("Indemnite forfaitaire pour frais de recouvrement : 40 EUR", "PMT")),
    new InvoiceOptions().addRule(RuleSet.BR_FR));

ValidationResult result = client.validateFacturX(Path.of("facture.pdf"), new ValidateOptions().addRule(RuleSet.BR_FR));
```

The BR-FR rules ask for the billing mode in `businessProcessType`, for example `B1` for goods, `S1` for services or `M1` for mixed invoices. French invoices also carry the legal mentions as notes with the subject codes `PMT`, `PMD` and `AAB`. Violations come back with the official rule identifiers and French messages.

## Profiles

EN 16931 is the default. To create another profile, set its identifier from `FacturXProfiles` as the invoice's `specificationId`:

```java
byte[] basic = client.createFacturX(invoice.setSpecificationId(FacturXProfiles.BASIC));
```

`MINIMUM` and `BASIC_WL` carry header data only and serve as booking aids. `BASIC` is the lowest profile the French mandate accepts, and `EXTENDED` adds data for complex invoicing.

## Use your own PDF layout

To keep your own invoice PDF, create the CII XML from the same invoice object and embed it into your PDF. The XML carries the EN 16931 profile, so `embedFacturX` accepts it as it is:

```java
String xml = client.createCii(invoice, new InvoiceOptions().setLanguage(PdfLanguage.FR).addRule(RuleSet.BR_FR));
byte[] pdf = client.embedFacturX(FileUpload.of(Path.of("my-invoice.pdf")), FileUpload.ofXml(xml));
```

## Create a UBL invoice

`createUbl` builds a UBL 2.1 XML from the same invoice object, with the Peppol BIS Billing 3.0 profile unless you pick another `UblProfile`:

```java
String ubl = client.createUbl(invoice, new InvoiceOptions().setProfile(UblProfile.PEPPOL_BIS_3));
```

## Send an invoice by email

`emailFacturX` creates the same document as `createFacturX`, then sends it as a real email from your own address. The sender must be on a domain added and verified on the Verified email domains page of the dashboard:

```java
var email = EmailMessage.of("factures@yourcompany.com", "compta@customer.example")
    .setFromName("Your Company Billing")
    .addCc("accounting@yourcompany.com");

EmailSendResult sent = client.emailFacturX(invoice, email);
System.out.println(sent.getMessageId());
```

`emailEmbeddedFacturX(pdf, xml, email)` sends a Factur-X PDF built from your own PDF and CII XML, always with full validation. Pass `new ValidateOptions().addRule(RuleSet.BR_FR)` as a fourth argument to apply the BR-FR rules too. The subject and message are optional, and a sensible default applies when you leave them out.

The result carries the message id, not the file. `accepted` means the email was handed over for delivery, not that the recipient's server has received it yet. Only HTTP 429 is retried on these calls, so a failed call never sends a second email by itself. After an `InvoiceXmlConnectionException` the email may or may not have gone out, so check before you send it again.

A refused send raises `InvoiceXmlApiException`. Branch on `getErrorCode()`: `ErrorCodes.EMAIL_DOMAIN_NOT_VERIFIED`, `ErrorCodes.EMAIL_SENDING_DISABLED`, or `ErrorCodes.EMAIL_NOT_IN_PLAN` when your plan does not include this (see [pricing](https://www.invoicexml.com/pricing)). A bad address raises `InvoiceValidationException` with the field in its findings. See the [email guide](https://www.invoicexml.com/docs/api/create/facturx/email) for the details.

## Rejected invoices

Every create call validates the invoice before a document is produced. When rules are violated, the call throws `InvoiceValidationException` with the findings, so a non-compliant invoice never leaves your system:

```java
try {
    client.createFacturX(invoice);
} catch (InvoiceValidationException e) {
    for (ValidationFinding f : e.getErrors()) {
        System.out.println(f.getRule() + " " + f.getFields() + ": " + f.getMessage());
    }
}
```

Each finding carries the rule id (for example `BR-CO-10`), the layer (`xsd`, `en16931` or `cius`), a plain-language message, the field paths into your invoice, and the verbatim validator output.

## Validate incoming invoices

Valid and invalid invoices both return a `ValidationResult`. Branch on `isValid()`:

```java
ValidationResult result = client.validateFacturX(Path.of("facture-fournisseur.pdf"));
if (!result.isValid()) {
    result.getErrors().forEach(System.out::println);
}
```

The result also carries the detected profile, the schema and Schematron verdicts, and a field report with one row per invoice field.

## Validation reports

`validateFacturXReport` runs the same validation and returns a printable PDF report with the verdict. A failed verdict is a normal result, not an exception:

```java
ValidationReport report = client.validateFacturXReport(FileUpload.of(Path.of("facture.pdf")), new ReportOptions()
    .addRule(RuleSet.BR_FR)
    .setValidationLanguage(PdfLanguage.FR));

Files.write(Path.of("rapport.pdf"), report.getFile());
if (Boolean.FALSE.equals(report.getValid())) {
    System.out.println("The invoice failed validation");
}
```

`getValid()` is `true`, `false`, or `null` when the response carries no verdict. A missing or malformed file still raises an exception. See the [report guide](https://www.invoicexml.com/docs/api/validate/facturx/report) for the details.

## Render an invoice as PDF

`renderCiiToPdf` turns the CII XML of a Factur-X invoice into a readable PDF:

```java
byte[] pdf = client.renderCiiToPdf(FileUpload.of(Path.of("factur-x.xml")), new RenderOptions()
    .setLanguage(PdfLanguage.FR)
    .setLogoUrl("https://example.com/logo.png")
    .setIncludeQrCode(true));
```

English is the default language, and the logo and the EPC payment QR code are optional. See the [render guide](https://www.invoicexml.com/docs/api/render/cii/to/pdf) for the details.

## Convert between formats

Each conversion has its own method, named after its route. Conversions to XML return a `String`, conversions to Factur-X return the PDF:

```java
String ubl = client.convertFacturXToUbl(Path.of("facture.pdf"));
byte[] pdf = client.convertUblToFacturX(FileUpload.of(Path.of("facture.xml")),
    new ConvertToPdfOptions().setLanguage(PdfLanguage.FR));
```

The result is validated before it is returned, and a rejected conversion throws `InvoiceValidationException`. `setValidationLanguage` on `ConvertOptions` or `ConvertToPdfOptions` words its findings. See the [convert guide](https://www.invoicexml.com/docs/api/convert/facturx/to/ubl) for the details.

## Transform a PDF invoice

`transformToFacturX` builds a Factur-X PDF from a PDF invoice. AI reads the PDF, so a person must check the result before it is used.

```java
byte[] facturx = client.transformToFacturX(FileUpload.of(Path.of("invoice.pdf")), new TransformOptions()
    .setLanguage(PdfLanguage.FR));
```

Without a language, the PDF uses the language the invoice is written in. `setPreserveOriginalPdf(true)` keeps the uploaded pages and only embeds the XML. Otherwise the PDF is rendered fresh and ends with an Electronic invoice details section for checking the technical values, which `setIncludeAdvancedProperties(false)` leaves out. A 429 on this method is not retried automatically, so wait for `Retry-After` before calling again if you want to retry. See the [transform guide](https://www.invoicexml.com/docs/api/transform/to/facturx) for the details.

## Errors, rate limits and retries

All exceptions are unchecked and extend `InvoiceXmlException`.

| Exception | When |
|---|---|
| `InvoiceValidationException` | HTTP 400 with rule findings |
| `AuthenticationException` | HTTP 401, the API key is missing or inactive |
| `PayloadTooLargeException` | HTTP 413, a file exceeds 20 MB |
| `RateLimitException` | HTTP 429 after any automatic retries |
| `InvoiceXmlApiException` | Any other error response, with `getErrorCode()` from `ErrorCodes` |
| `InvoiceXmlConnectionException` | The API could not be reached |

On HTTP 429 every call except transform waits for the `Retry-After` delay and retries, three times by default. A 429 is not billed.

```java
var client = FacturXClient.builder()
    .apiKey(apiKey)
    .maxRetries(5)
    .maxRetryWait(Duration.ofSeconds(30))
    .timeout(Duration.ofSeconds(60))
    .build();
```

The client is immutable and thread-safe. Create one instance and share it. To reuse your own `java.net.http.HttpClient` (proxy, executor, SSL context), pass `new JdkHttpTransport(httpClient)` to `transport(...)`.

## Why InvoiceXML

- **Rule sets updated on their legal effective dates.** New Factur-X, BR-FR and EN 16931 rule versions go live on the API the day they take legal effect. The rules run on the server, so you never update this library or redeploy for a new rule version.
- **Validated before delivery.** Every document is checked against the official XSD and Schematron rules before it is returned.
- **Zero data retention.** Invoices are processed in memory and purged when the response is delivered. Nothing is stored or logged, and nothing is used for AI training. When you email an invoice, it goes to our email service for delivery, and we keep no copy.
- **Processing in Frankfurt.** All processing happens in the EU, in Frankfurt, Germany. See [www.invoicexml.com/trust](https://www.invoicexml.com/trust).
- **Integration assistance.** Questions about your integration go to [support@invoicexml.com](mailto:support@invoicexml.com), from setup to your first production invoice.

## Documentation

- API reference: [api.invoicexml.com/v1/scalar](https://api.invoicexml.com/v1/scalar/)
- OpenAPI 3.1 schema: [api.invoicexml.com/v1/openapi](https://api.invoicexml.com/v1/openapi)
- ZUGFeRD and XRechnung: see the companion library [invoicexml-java-zugferd-xrechnung](https://github.com/InvoiceXML/invoicexml-java-zugferd-xrechnung)

## Development

```bash
./mvnw verify
```

The unit tests run against a stubbed HTTP layer. A live suite against the real API runs only when you opt in, and it consumes about 13 credits per run:

```bash
INVOICEXML_API_KEY=your-key ./mvnw verify -Dinvoicexml.live=true
```

The classes in `com.invoicexml.facturx.model` are generated from the OpenAPI schema. To refresh them after a schema change, run `node tools/generate-models.mjs --fetch` (Node 18 or later) and commit the result.

## License

MIT. See [LICENSE](LICENSE).
