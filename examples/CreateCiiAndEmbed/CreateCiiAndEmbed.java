// Builds a CII XML from your invoice data, then embeds it into a PDF you already have.
// Use this when your own system prints the invoice PDF and you want it to become a
// Factur-X invoice. The CII XML declares the EN 16931 profile, so it embeds as it is.

import com.invoicexml.facturx.FacturXClient;
import com.invoicexml.facturx.FileUpload;
import com.invoicexml.facturx.InvoiceValidationException;
import com.invoicexml.facturx.InvoiceXmlApiException;
import com.invoicexml.facturx.model.BuyerParty;
import com.invoicexml.facturx.model.BuyerPostalAddress;
import com.invoicexml.facturx.model.InvoiceDocument;
import com.invoicexml.facturx.model.InvoiceLine;
import com.invoicexml.facturx.model.InvoiceOptions;
import com.invoicexml.facturx.model.ItemInformation;
import com.invoicexml.facturx.model.LineVatInformation;
import com.invoicexml.facturx.model.PdfLanguage;
import com.invoicexml.facturx.model.PriceDetails;
import com.invoicexml.facturx.model.RuleSet;
import com.invoicexml.facturx.model.SellerParty;
import com.invoicexml.facturx.model.SellerPostalAddress;
import com.invoicexml.facturx.model.VatCategoryCodes;
import com.invoicexml.facturx.validation.ValidationFinding;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public class CreateCiiAndEmbed {

    // Replace these values with your own.
    private static final String API_KEY = "YOUR_API_KEY";
    private static final String PDF_PATH = "factures/F-2026-001.pdf"; // the PDF your system printed
    private static final String OUTPUT_PATH = "F-2026-001-facturx.pdf";

    public static void main(String[] args) throws IOException {
        // Read the PDF first, so a wrong path stops the example before any API call.
        FileUpload pdf;
        try {
            Path path = Path.of(PDF_PATH);
            pdf = FileUpload.of(path.getFileName().toString(), Files.readAllBytes(path));
        } catch (NoSuchFileException e) {
            System.out.println("Could not find " + PDF_PATH + ".");
            System.out.println("Set PDF_PATH at the top of CreateCiiAndEmbed.java to a PDF on your machine.");
            return;
        }

        FacturXClient client = FacturXClient.create(API_KEY);

        InvoiceDocument invoice = new InvoiceDocument()
                .setInvoiceNumber("F-2026-001")
                .setCurrency("EUR")
                .setSeller(new SellerParty()
                        .setName("Atelier Dupont SARL")
                        .setVatIdentifier("FR32123456789")
                        .setPostalAddress(new SellerPostalAddress().setCity("Paris").setPostCode("75011").setCountry("FR")))
                .setBuyer(new BuyerParty()
                        .setName("Client Exemple SA")
                        .setPostalAddress(new BuyerPostalAddress().setCity("Lyon").setPostCode("69002").setCountry("FR")))
                .addLine(new InvoiceLine()
                        .setQuantity(new BigDecimal("10"))
                        .setUnitCode("HUR") // hour
                        .setItem(new ItemInformation().setName("Prestation de conseil"))
                        .setPriceDetails(new PriceDetails().setNetPrice(new BigDecimal("120")))
                        .setVatInformation(new LineVatInformation()
                                .setCategoryCode(VatCategoryCodes.STANDARD_RATE)
                                .setRate(new BigDecimal("20"))));

        try {
            // 1. Build the CII XML from the invoice data. The language words the default texts, such as the payment terms.
            InvoiceOptions options = new InvoiceOptions().setLanguage(PdfLanguage.FR);
            // options.addRule(RuleSet.BR_FR); // optional: also check the French BR-FR rules (they need more data, see the README)
            String cii = client.createCii(invoice, options);

            // 2. Embed the XML into your PDF. The result is a Factur-X PDF/A-3.
            byte[] facturX = client.embedFacturX(pdf, FileUpload.ofXml(cii));

            Files.write(Path.of(OUTPUT_PATH), facturX);
            System.out.println("Saved the Factur-X invoice to " + OUTPUT_PATH + ".");
        } catch (InvoiceValidationException e) {
            // The invoice data would not produce a compliant invoice. Each finding names the field to fix.
            System.out.println("The invoice is not compliant:");
            for (ValidationFinding finding : e.getErrors()) {
                System.out.println("  [" + finding.getRule() + "] " + finding.getMessage()
                        + " (" + String.join(", ", finding.getFields()) + ")");
            }
        } catch (InvoiceXmlApiException e) {
            System.out.println("The API refused the request: " + e.getMessage());
        }
    }
}
