// Validates an incoming Factur-X invoice against the rules of the profile it declares, and
// optionally the French BR-FR rules. A failed validation is a normal result, not an exception:
// you get the verdict and every finding with the rule, the message and the field it concerns.
// The example also saves the printable validation report PDF, for example to send back to a supplier.

import com.invoicexml.facturx.FacturXClient;
import com.invoicexml.facturx.FileUpload;
import com.invoicexml.facturx.InvoiceXmlApiException;
import com.invoicexml.facturx.ReportOptions;
import com.invoicexml.facturx.ValidateOptions;
import com.invoicexml.facturx.model.PdfLanguage;
import com.invoicexml.facturx.model.RuleSet;
import com.invoicexml.facturx.validation.ValidationFinding;
import com.invoicexml.facturx.validation.ValidationReport;
import com.invoicexml.facturx.validation.ValidationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

public class ValidateInvoices {

    // Replace these values with your own.
    private static final String API_KEY = "YOUR_API_KEY";
    private static final String FACTURX_PDF_PATH = "factures/facture-fournisseur.pdf";
    private static final String REPORT_PATH = "facture-fournisseur-rapport.pdf";

    // Optional: also check the French BR-FR rules, the set the French platforms apply to domestic invoices.
    private static final List<RuleSet> RULES = List.of(); // for example List.of(RuleSet.BR_FR)

    public static void main(String[] args) throws IOException {
        // Read the PDF first, so a wrong path stops the example before any API call.
        FileUpload pdf;
        try {
            Path path = Path.of(FACTURX_PDF_PATH);
            pdf = FileUpload.of(path.getFileName().toString(), Files.readAllBytes(path));
        } catch (NoSuchFileException e) {
            System.out.println("Could not find " + FACTURX_PDF_PATH + ".");
            System.out.println("Set FACTURX_PDF_PATH at the top of ValidateInvoices.java to a Factur-X PDF on your machine.");
            return;
        }

        FacturXClient client = FacturXClient.create(API_KEY);

        try {
            // 1. The verdict and the findings as data.
            ValidateOptions validateOptions = new ValidateOptions();
            RULES.forEach(validateOptions::addRule);
            ValidationResult result = client.validateFacturX(pdf, validateOptions);
            System.out.println((result.isValid() ? "Valid" : "Not valid") + ". " + result.getDetail());

            for (ValidationFinding error : result.getErrors()) {
                System.out.println("  error   [" + error.getRule() + "] " + error.getMessage()
                        + " (" + String.join(", ", error.getFields()) + ")");
            }

            // Warnings never make an invoice invalid, but are worth reviewing.
            for (ValidationFinding warning : result.getWarnings()) {
                System.out.println("  warning [" + warning.getRule() + "] " + warning.getMessage()
                        + " (" + String.join(", ", warning.getFields()) + ")");
            }

            // 2. The same check as a printable PDF report, worded in French.
            ReportOptions reportOptions = new ReportOptions().setValidationLanguage(PdfLanguage.FR);
            RULES.forEach(reportOptions::addRule);
            ValidationReport report = client.validateFacturXReport(pdf, reportOptions);
            Files.write(Path.of(REPORT_PATH), report.getFile());
            System.out.println("Saved the validation report to " + REPORT_PATH + " (valid: " + report.getValid() + ").");
        } catch (InvoiceXmlApiException e) {
            // For example an invalid API key, or a file that is not a PDF.
            System.out.println("The API refused the request: " + e.getMessage());
        }
    }
}
