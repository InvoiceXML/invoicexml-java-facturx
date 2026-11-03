// Turns an ordinary PDF invoice, for example one from an older billing system or a supplier,
// into a Factur-X invoice. AI reads the PDF, so a person must check the result before it is used.
// The rendered PDF ends with a section of technical properties to make that check easy.

import com.invoicexml.facturx.ErrorCodes;
import com.invoicexml.facturx.FacturXClient;
import com.invoicexml.facturx.FileUpload;
import com.invoicexml.facturx.InvoiceValidationException;
import com.invoicexml.facturx.InvoiceXmlApiException;
import com.invoicexml.facturx.RateLimitException;
import com.invoicexml.facturx.TransformOptions;
import com.invoicexml.facturx.model.PdfLanguage;
import com.invoicexml.facturx.validation.ValidationFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

public class TransformToFacturX {

    // Replace these values with your own.
    private static final String API_KEY = "YOUR_API_KEY";
    private static final String PDF_PATH = "factures/F-2026-001.pdf"; // an ordinary PDF invoice
    private static final String OUTPUT_PATH = "F-2026-001-facturx.pdf";

    public static void main(String[] args) throws IOException {
        // Read the PDF first, so a wrong path stops the example before any API call.
        FileUpload pdf;
        try {
            Path path = Path.of(PDF_PATH);
            pdf = FileUpload.of(path.getFileName().toString(), Files.readAllBytes(path));
        } catch (NoSuchFileException e) {
            System.out.println("Could not find " + PDF_PATH + ".");
            System.out.println("Set PDF_PATH at the top of TransformToFacturX.java to a PDF invoice on your machine.");
            return;
        }

        FacturXClient client = FacturXClient.create(API_KEY);

        try {
            TransformOptions options = new TransformOptions();
            // options.setLanguage(PdfLanguage.FR);           // optional: left empty, the language the invoice is written in is used
            // options.setPreserveOriginalPdf(true);          // optional: keep your PDF as the visual layer instead of a fresh layout
            // options.setIncludeAdvancedProperties(false);   // optional: leave out the technical properties section

            byte[] facturX = client.transformToFacturX(pdf, options);

            Files.write(Path.of(OUTPUT_PATH), facturX);
            System.out.println("Saved the Factur-X invoice to " + OUTPUT_PATH + ". Check it before you send or book it.");
        } catch (RateLimitException e) {
            // Transform calls are not retried automatically, because a retry repeats the AI read.
            System.out.println(e.getRetryAfter() != null
                    ? "Too many requests. Wait " + e.getRetryAfter().getSeconds() + " seconds before trying again."
                    : "Too many requests. Wait a moment before trying again.");
        } catch (InvoiceXmlApiException e) {
            if (Objects.equals(e.getErrorCode(), ErrorCodes.TOTALS_MISMATCH)) {
                // The amounts read from the PDF do not add up to its printed total, so no invoice was created.
                System.out.println("The lines, allowances, charges and VAT read from the PDF do not add up to its printed total.");
                System.out.println("Check the PDF, or create the invoice from your own data with createFacturX instead.");
            } else if (e instanceof InvoiceValidationException) {
                // The data read from the PDF would not make a compliant Factur-X invoice.
                System.out.println("The invoice read from the PDF is not compliant:");
                for (ValidationFinding finding : ((InvoiceValidationException) e).getErrors()) {
                    System.out.println("  [" + finding.getRule() + "] " + finding.getMessage()
                            + " (" + String.join(", ", finding.getFields()) + ")");
                }
            } else {
                // For example an invalid API key, or a file that is not a PDF invoice.
                System.out.println("The API refused the request: " + e.getMessage());
            }
        }
    }
}
