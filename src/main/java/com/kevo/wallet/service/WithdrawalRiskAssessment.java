package com.kevo.wallet.service;

import java.util.List;

public record WithdrawalRiskAssessment(String riskLevel, List<RiskFlag> flags) {

    public boolean requiresManualReview() {
        return "HIGH".equals(riskLevel);
    }

    // A single joined string of every flag's reason, for storage/display -
    // never a bare score, always the reasons behind it.
    public String reviewNotes() {
        if (flags.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (RiskFlag flag : flags) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(flag.code()).append(": ").append(flag.reason());
        }
        return sb.toString();
    }

    public record RiskFlag(String code, String reason) {}
}
