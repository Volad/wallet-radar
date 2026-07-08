package com.walletradar.application.costbasis.application.replay.dispatch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires ordered {@link ReplayRouteHandler} beans into the route-level registry (Track A / A5).
 */
@Configuration
public class ReplayRouteHandlerConfiguration {

    @Bean
    ReplayRouteHandlerRegistry replayRouteHandlerRegistry(List<ReplayRouteHandler> handlers) {
        return new ReplayRouteHandlerRegistry(handlers);
    }
}
