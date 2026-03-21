package com.walletradar.ingestion.pipeline.classification.special;

final class SpecialHandlerSupport {

    private SpecialHandlerSupport() {
    }

    static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.contains(needle);
    }

    static boolean containsAny(String haystack, String... needles) {
        if (haystack == null) {
            return false;
        }
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
