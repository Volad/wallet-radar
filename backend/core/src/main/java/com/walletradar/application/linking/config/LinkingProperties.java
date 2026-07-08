package com.walletradar.application.linking.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings for the dedicated linking stage.
 */
@ConfigurationProperties(prefix = "walletradar.ingestion.linking")
@NoArgsConstructor
@Getter
@Setter
public class LinkingProperties {

    private boolean enabled = true;

    private int batchSize = 500;
}
