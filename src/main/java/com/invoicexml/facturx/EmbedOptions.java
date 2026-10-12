package com.invoicexml.facturx;

import com.invoicexml.facturx.model.RuleSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Options for {@link FacturXClient#embedFacturX(FileUpload, FileUpload, EmbedOptions)}.
 */
public final class EmbedOptions {

    private boolean skipValidation;
    private final List<RuleSet> rules = new ArrayList<>();

    /** Creates options with the defaults: full validation before embedding, no extra rule sets. */
    public EmbedOptions() {
    }

    /**
     * Returns whether business rule validation is skipped.
     *
     * @return {@code true} for packaging-only mode
     */
    public boolean isSkipValidation() {
        return skipValidation;
    }

    /**
     * Selects packaging-only mode. By default the XML runs through the full rule
     * set of {@code /v1/validate/facturx} before it is embedded, and an invoice
     * with errors is rejected. With {@code true} only the structural profile
     * schema check runs, for callers who knowingly package transitional XML.
     *
     * @param skipValidation {@code true} to skip the business rules
     * @return this instance
     */
    public EmbedOptions setSkipValidation(boolean skipValidation) {
        this.skipValidation = skipValidation;
        return this;
    }

    /**
     * Returns the extra rule sets applied before embedding.
     *
     * @return the rule sets, never {@code null}
     */
    public List<RuleSet> getRules() {
        return Collections.unmodifiableList(rules);
    }

    /**
     * Adds an extra rule set to apply before embedding, such as {@link RuleSet#BR_FR}.
     *
     * @param rule the rule set
     * @return this instance
     */
    public EmbedOptions addRule(RuleSet rule) {
        rules.add(rule);
        return this;
    }
}
