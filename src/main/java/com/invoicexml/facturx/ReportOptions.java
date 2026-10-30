package com.invoicexml.facturx;

import com.invoicexml.facturx.model.PdfLanguage;
import com.invoicexml.facturx.model.RuleSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Options for {@link FacturXClient#validateFacturXReport(FileUpload, ReportOptions)}.
 */
public final class ReportOptions {

    private final List<RuleSet> rules = new ArrayList<>();
    private PdfLanguage validationLanguage;

    /** Creates options with the defaults: the declared profile's rules only, a report in English. */
    public ReportOptions() {
    }

    /**
     * Returns the extra rule sets applied on top of the profile rules.
     *
     * @return the rule sets, never {@code null}
     */
    public List<RuleSet> getRules() {
        return Collections.unmodifiableList(rules);
    }

    /**
     * Adds an extra rule set, such as {@link RuleSet#BR_FR} for the French
     * mandate. It needs at least the BASIC profile.
     *
     * @param rule the rule set
     * @return this instance
     */
    public ReportOptions addRule(RuleSet rule) {
        rules.add(rule);
        return this;
    }

    /**
     * Returns the language of the report.
     *
     * @return the language, or {@code null} for the default (English)
     */
    public PdfLanguage getValidationLanguage() {
        return validationLanguage;
    }

    /**
     * Sets the language of the whole report.
     *
     * @param validationLanguage the language, or {@code null} for English
     * @return this instance
     */
    public ReportOptions setValidationLanguage(PdfLanguage validationLanguage) {
        this.validationLanguage = validationLanguage;
        return this;
    }
}
