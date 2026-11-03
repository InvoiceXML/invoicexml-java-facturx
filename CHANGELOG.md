# Changelog

## 0.5.0

- Add `parseJson`, which reads a typed, scanned or photographed PDF invoice into the invoice model with AI and returns a `ParsedInvoice` with a confidence score per area and the language of the invoice. A person must check the data before it is used. A 429 on this call is not retried automatically.
- Breaking: `renderCiiToPdf` is replaced by `renderXmlToPdf`, which renders any invoice XML (CII, UBL or XRechnung) and detects the format itself.
- Add `examples/`: one runnable program per common task, built on the library source in this repository.

## 0.4.0

- Add `createCii`, which builds a plain CII XML from the invoice model, ready for `embedFacturX` to turn your own PDF into a Factur-X invoice.
- Add `createUbl`, which builds a UBL XML in the profile set by the new `InvoiceOptions.setProfile` (`UblProfile`, Peppol BIS Billing 3.0 by default).
- Add `ErrorCodes.TOTALS_MISMATCH` (4024), returned when a transformed PDF's amounts do not add up to its printed total.

## 0.3.0

- Add rendering of CII XML as PDF, conversion between formats, the Factur-X validation report and transform to Factur-X.
- Add email delivery: `emailFacturX` and `emailEmbeddedFacturX` send the invoice from your own verified domain.

## 0.2.0

- Add `despatchAdviceReference` (BT-16, the delivery note number) to `InvoiceDocument`.

## 0.1.1

- Link the website at https://www.invoicexml.com in the package metadata (the Maven Project URL now points to the website), the README and error messages.

## 0.1.0

First release.

- `FacturXClient` with create, validate, embed and extract calls for Factur-X.
- Optional French BR-FR rule set on create, validate and embed.
- EN 16931 invoice model generated from the InvoiceXML OpenAPI schema.
- Typed validation results and rule findings on rejected create requests.
- Automatic retry on HTTP 429 with `Retry-After` support.
