// Creates a Factur-X invoice that keeps your own PDF layout.
// The API downloads your PDF from a public URL, embeds the XML it generates from the invoice
// data into it, and returns a Factur-X PDF/A-3. Nothing about your PDF's look changes.

import com.invoicexml.facturx.FacturXClient;
import com.invoicexml.facturx.InvoiceValidationException;
import com.invoicexml.facturx.InvoiceXmlApiException;
import com.invoicexml.facturx.model.BuyerParty;
import com.invoicexml.facturx.model.BuyerPostalAddress;
import com.invoicexml.facturx.model.InvoiceDocument;
import com.invoicexml.facturx.model.InvoiceLine;
import com.invoicexml.facturx.model.InvoiceOptions;
import com.invoicexml.facturx.model.ItemInformation;
import com.invoicexml.facturx.model.LineVatInformation;
import com.invoicexml.facturx.model.PriceDetails;
import com.invoicexml.facturx.model.RuleSet;
import com.invoicexml.facturx.model.SellerParty;
import com.invoicexml.facturx.model.SellerPostalAddress;
import com.invoicexml.facturx.model.VatCategoryCodes;
import com.invoicexml.facturx.validation.ValidationFinding;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

public class CreateFacturXWithOwnPdf {

    // Replace these values with your own.
    private static final String API_KEY = "YOUR_API_KEY";
    private static final String PDF_URL = "https://example.com/factures/F-2026-001.pdf"; // public http(s) URL of your PDF
    private static final String OUTPUT_PATH = "F-2026-001-facturx.pdf";

    public static void main(String[] args) throws IOException {
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
            // The PDF URL makes your PDF the visual layer. Without it, the API renders its own invoice layout.
            InvoiceOptions options = new InvoiceOptions().setPdfUrl(PDF_URL);
            // options.addRule(RuleSet.BR_FR); // optional: also check the French BR-FR rules (they need more data, see the README)

            byte[] facturX = client.createFacturX(invoice, options);
            Files.write(Path.of(OUTPUT_PATH), facturX);
            System.out.println("Saved the Factur-X invoice to " + OUTPUT_PATH + ".");
        } catch (InvoiceValidationException e) {
            // The invoice data would not produce a compliant Factur-X invoice. Each finding names the field to fix.
            System.out.println("The invoice is not compliant:");
            for (ValidationFinding finding : e.getErrors()) {
                System.out.println("  [" + finding.getRule() + "] " + finding.getMessage()
                        + " (" + String.join(", ", finding.getFields()) + ")");
            }
        } catch (InvoiceXmlApiException e) {
            // For example an invalid API key, or a PDF URL the API could not download.
            System.out.println("The API refused the request: " + e.getMessage());
        }
    }
}
