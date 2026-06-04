package com.astra.governance.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ContentGovernanceService {

    private static final List<Pattern> PII_PATTERNS = List.of(
        Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b"),   // email
        Pattern.compile("\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b"),                            // US phone
        Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),                                    // SSN
        Pattern.compile("\\b4[0-9]{12}(?:[0-9]{3})?\\b"),                                 // Visa
        Pattern.compile("\\b5[1-5][0-9]{14}\\b"),                                          // Mastercard
        Pattern.compile("\\b3[47][0-9]{13}\\b")                                            // Amex
    );

    private static final List<String> PII_TYPE_NAMES = List.of(
        "email", "phone", "ssn", "visa_card", "mastercard", "amex_card"
    );

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("(?i)ignore (previous|all|prior) (instructions|context|rules)"),
        Pattern.compile("(?i)you are now (a different|an? unrestricted|a rogue|a new)"),
        Pattern.compile("(?i)pretend (you are|to be|that you are)"),
        Pattern.compile("(?i)(jailbreak|DAN mode|developer mode|god mode)"),
        Pattern.compile("(?i)(disregard|forget|override) (your|all|safety|ethical)"),
        Pattern.compile("(?i)system prompt|<\\|im_start\\||\\[INST\\]")
    );

    private static final Set<String> TOXIC_CATEGORIES = Set.of(
        "hate speech", "self-harm", "explicit violence", "illegal activities",
        "child exploitation", "terrorism"
    );

    private static final Map<String, String> KNOWN_POLICIES = Map.of(
        "no_pii",        "Block requests containing personally identifiable information",
        "no_toxicity",   "Block requests with toxic or harmful content",
        "no_injection",  "Block prompt injection attempts",
        "rate_limit",    "Enforce per-tenant rate limits",
        "content_filter","Apply content safety filters to all requests and responses"
    );

    public Map<String, Object> checkContent(String content, String type) {
        if (content == null || content.isBlank()) {
            return Map.of("safe", true, "issues", List.of(), "action", "allow");
        }

        List<String> issues = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();

        List<String> piiTypes = detectPII(content);
        if (!piiTypes.isEmpty()) {
            issues.add("pii_detected");
            details.put("pii_types", piiTypes);
        }

        if ("prompt".equalsIgnoreCase(type)) {
            List<String> injections = detectInjection(content);
            if (!injections.isEmpty()) {
                issues.add("prompt_injection_detected");
                details.put("injection_patterns_matched", injections.size());
            }
        }

        List<String> toxicMatches = detectToxicity(content);
        if (!toxicMatches.isEmpty()) {
            issues.add("toxic_content_detected");
            details.put("toxic_categories", toxicMatches);
        }

        boolean safe = issues.isEmpty();
        log.debug("Content check: type={}, safe={}, issues={}", type, safe, issues);

        return Map.of(
            "safe", safe,
            "issues", issues,
            "details", details,
            "action", safe ? "allow" : "block",
            "content_type", type != null ? type : "unknown"
        );
    }

    public Map<String, Object> validatePolicy(String policy, Object data) {
        if (policy == null || policy.isBlank()) {
            return Map.of(
                "valid", false,
                "message", "Policy name is required",
                "known_policies", KNOWN_POLICIES.keySet()
            );
        }

        boolean known = KNOWN_POLICIES.containsKey(policy);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policy", policy);
        result.put("valid", known);
        result.put("description", known ? KNOWN_POLICIES.get(policy) : "Unknown policy");
        result.put("message", known ? "Policy '" + policy + "' is valid" : "Unknown policy: " + policy);
        result.put("known_policies", KNOWN_POLICIES.keySet());
        return result;
    }

    private List<String> detectPII(String content) {
        List<String> detected = new ArrayList<>();
        for (int i = 0; i < PII_PATTERNS.size(); i++) {
            if (PII_PATTERNS.get(i).matcher(content).find()) {
                detected.add(PII_TYPE_NAMES.get(i));
            }
        }
        return detected;
    }

    private List<String> detectInjection(String content) {
        List<String> matched = new ArrayList<>();
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(content).find()) {
                matched.add(p.pattern());
            }
        }
        return matched;
    }

    private List<String> detectToxicity(String content) {
        String lower = content.toLowerCase();
        List<String> detected = new ArrayList<>();
        for (String category : TOXIC_CATEGORIES) {
            if (lower.contains(category)) {
                detected.add(category);
            }
        }
        return detected;
    }
}
