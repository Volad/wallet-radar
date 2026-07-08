package com.walletradar.testsupport;

import com.walletradar.platform.networks.descriptor.NetworkProperties;
import com.walletradar.platform.networks.descriptor.NetworkRegistry;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.util.List;

/**
 * Loads {@link NetworkRegistry} from classpath {@code network-descriptors.yml} for unit tests.
 */
public final class NetworkTestFixtures {

    private static final NetworkRegistry REGISTRY = loadRegistry();

    private NetworkTestFixtures() {
    }

    public static NetworkRegistry registry() {
        return REGISTRY;
    }

    private static NetworkRegistry loadRegistry() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                    "network-descriptors",
                    new ClassPathResource("network-descriptors.yml")
            );
            StandardEnvironment environment = new StandardEnvironment();
            sources.forEach(environment.getPropertySources()::addLast);
            NetworkProperties properties = Binder.get(environment)
                    .bind("walletradar.networks", NetworkProperties.class)
                    .orElseGet(NetworkProperties::new);
            return new NetworkRegistry(properties);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load network-descriptors.yml for tests", ex);
        }
    }
}
