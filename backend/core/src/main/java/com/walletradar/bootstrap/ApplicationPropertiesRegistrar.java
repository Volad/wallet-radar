package com.walletradar.bootstrap;

import com.walletradar.application.backfill.config.BackfillProperties;
import com.walletradar.application.linking.config.LinkingProperties;
import com.walletradar.application.normalization.config.BybitInternalTransferProperties;
import com.walletradar.application.normalization.config.BybitNormalizationProperties;
import com.walletradar.application.normalization.config.NativeSettlementRecoveryProperties;
import com.walletradar.application.normalization.config.OnChainClarificationProperties;
import com.walletradar.application.normalization.config.OnChainNormalizationProperties;
import com.walletradar.application.normalization.config.ScamFilterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers application-layer {@code @ConfigurationProperties} beans.
 * Keeps {@code platform.networks.config.IngestionAdapterConfig} free of upward dependencies.
 */
@Configuration
@EnableConfigurationProperties({
        BackfillProperties.class,
        ScamFilterProperties.class,
        OnChainNormalizationProperties.class,
        OnChainClarificationProperties.class,
        NativeSettlementRecoveryProperties.class,
        BybitNormalizationProperties.class,
        BybitInternalTransferProperties.class,
        LinkingProperties.class
})
public class ApplicationPropertiesRegistrar {
}
