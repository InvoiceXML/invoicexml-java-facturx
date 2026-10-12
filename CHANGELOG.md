# Changelog

## 0.1.0

First release.

- `FacturXClient` with create, validate, embed and extract calls for Factur-X.
- Optional French BR-FR rule set on create, validate and embed.
- EN 16931 invoice model generated from the InvoiceXML OpenAPI schema.
- Typed validation results and rule findings on rejected create requests.
- Automatic retry on HTTP 429 with `Retry-After` support.
