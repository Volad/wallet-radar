package com.walletradar.domain.session;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Installation-wide projection of tracked wallet addresses used by canonical normalization.
 */
@Document(collection = "tracked_wallets")
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TrackedWallet {

    @Id
    @EqualsAndHashCode.Include
    private String address;

    private int refCount;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
}
