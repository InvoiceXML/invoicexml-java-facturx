package com.invoicexml.facturx;

import com.invoicexml.facturx.model.RuleSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Options for {@link FacturXClient#validateFacturX(FileUpload, ValidateOptions)}.
 */
public final class ValidateOptions {

    private final List<RuleSet> rules = new ArrayList<>();

    /** Creates options with the defaults: the declared profile's rules only. */
    public ValidateOptions() {
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
    public ValidateOptions addRule(RuleSet rule) {
        rules.add(rule);
        return this;
    }
}
