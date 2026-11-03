// Converts a UBL invoice XML, for example one received over Peppol, into a Factur-X invoice:
// a readable PDF with the invoice data embedded as CII XML.

import com.invoicexml.facturx.ConvertToPdfOptions;
import com.invoicexml.facturx.FacturXClient;
import com.invoicexml.facturx.FileUpload;
import com.invoicexml.facturx.InvoiceValidationException;
import com.invoicexml.facturx.InvoiceXmlApiException;
import com.invoicexml.facturx.model.PdfLanguage;
import com.invoicexml.facturx.validation.ValidationFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public class ConvertUblToFacturX {

    // Replace these values with your own.
    private static final String API_KEY = "YOUR_API_KEY";
    private static final String UBL_XML_PATH = "factures/F-2026-001-ubl.xml";
    private static final String OUTPUT_PATH = "F-2026-001-facturx.pdf";

    public static void main(String[] args) throws IOException {
        // Read the XML first, so a wrong path stops the example before any API call.
        FileUpload ubl;
        try {
            Path path = Path.of(UBL_XML_PATH);
            ubl = FileUpload.of(path.getFileName().toString(), Files.readAllBytes(path));
        } catch (NoSuchFileException e) {
            System.out.println("Could not find " + UBL_XML_PATH + ".");
            System.out.println("Set UBL_XML_PATH at the top of ConvertUblToFacturX.java to a UBL invoice XML on your machine.");
            return;
        }

        FacturXClient client = FacturXClient.create(API_KEY);

        try {
            // The language sets the PDF layout and the default texts added when the UBL has none.
            byte[] facturX = client.convertUblToFacturX(ubl,
                    new ConvertToPdfOptions().setLanguage(PdfLanguage.FR));

            Files.write(Path.of(OUTPUT_PATH), facturX);
            System.out.println("Saved the Factur-X invoice to " + OUTPUT_PATH + ".");
        } catch (InvoiceValidationException e) {
            // The result would not be a compliant Factur-X invoice. Each finding names the field to fix.
            System.out.println("The converted invoice would not be compliant:");
            for (ValidationFinding finding : e.getErrors()) {
                System.out.println("  [" + finding.getRule() + "] " + finding.getMessage()
                        + " (" + String.join(", ", finding.getFields()) + ")");
            }
        } catch (InvoiceXmlApiException e) {
            System.out.println("The API refused the request: " + e.getMessage());
        }
    }
}
