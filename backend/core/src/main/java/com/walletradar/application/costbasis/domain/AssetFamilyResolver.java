package com.walletradar.application.costbasis.domain;

import org.springframework.stereotype.Component;

/**
 * Resolves {@link AssetFamily} labels from flow asset symbols (ADR-015 §D4).
 */
@Component
public class AssetFamilyResolver {

    public String resolveFamily(String assetSymbol) {
        return AssetFamily.resolve(assetSymbol);
    }
}
