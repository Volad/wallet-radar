package com.walletradar.domain.transaction.integration;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

import java.time.Instant;

/**
 * Immutable provider-neutral raw evidence row fetched from an external
 * integration API.
 */
@org.springframework.data.mongodb.core.mapping.Document(collection = "integration_raw_events")
@CompoundIndexes({
        @CompoundIndex(
                name = "integration_raw_session_provider_stream_time_idx",
                def = "{'sessionId': 1, 'provider': 1, 'stream': 1, 'occurredAt': 1}"
        ),
        @CompoundIndex(
                name = "integration_raw_integration_stream_key_idx",
                def = "{'integrationId': 1, 'stream': 1, 'providerEventKey': 1}",
                unique = true
        ),
        @CompoundIndex(
                name = "integration_raw_segment_idx",
                def = "{'segmentId': 1, 'fetchedAt': 1}"
        )
})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class IntegrationRawEvent {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private String sessionId;
    private String integrationId;
    private String provider;
    private String accountRef;
    private String stream;
    private String providerEventKey;
    private Instant occurredAt;
    private Instant fetchedAt;
    private String segmentId;
    private Document payload;
    private String ingestHash;
}
