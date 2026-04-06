package com.walletradar.domain.session;

import com.walletradar.domain.common.NetworkId;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stable accounting scope for same-owner continuity and session-scoped reads.
 *
 * <p>The universe is additive: members are never removed automatically when the
 * current UI session changes its visible wallet subset.</p>
 */
@Document(collection = "accounting_universes")
@CompoundIndex(name = "accounting_universe_member_ref_idx", def = "{'members.ref': 1}")
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AccountingUniverse {

    @Id
    @EqualsAndHashCode.Include
    private String id;

    private List<Member> members = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    @NoArgsConstructor
    @Getter
    @Setter
    public static class Member {
        private String ref;
        private MemberType type;
        private String provider;
        private List<NetworkId> networks = new ArrayList<>();
        private Instant firstSeenAt;
        private Instant lastSeenAt;
    }

    public enum MemberType {
        ON_CHAIN_WALLET,
        EXCHANGE_ACCOUNT
    }
}
