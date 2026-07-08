package com.walletradar.platform.networks.descriptor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NetworkProperties.class)
public class NetworkDescriptorConfiguration {
}
