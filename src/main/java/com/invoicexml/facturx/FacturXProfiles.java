package com.invoicexml.facturx;

/**
 * Specification identifiers of the official Factur-X profiles. Set one as the
 * invoice's {@code specificationId} to select that profile on create. Without
 * one, the API creates the EN 16931 profile.
 */
public final class FacturXProfiles {

    /** MINIMUM: header data only, a booking aid rather than a full e-invoice. */
    public static final String MINIMUM = "urn:factur-x.eu:1p0:minimum";

    /** BASIC WL: header and totals without lines, a booking aid rather than a full e-invoice. */
    public static final String BASIC_WL = "urn:factur-x.eu:1p0:basicwl";

    /** BASIC: a subset of EN 16931 with line items. The lowest profile the French mandate accepts. */
    public static final String BASIC = "urn:cen.eu:en16931:2017#compliant#urn:factur-x.eu:1p0:basic";

    /** EN 16931: the European standard invoice, the default and recommended profile. */
    public static final String EN16931 = "urn:cen.eu:en16931:2017";

    /** EXTENDED: EN 16931 plus additional business data for complex invoicing. */
    public static final String EXTENDED = "urn:cen.eu:en16931:2017#conformant#urn:factur-x.eu:1p0:extended";

    private FacturXProfiles() {
    }
}
