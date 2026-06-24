package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.session.AccountingUniverse;
import com.walletradar.domain.session.AccountingUniverseRepository;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddressPoisoningDetectorTest {

    private static final String WALLET_A = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
    private static final String WALLET_B = "0xf03b52e8686b962e051a6075a06b96cb8a663021";

    @Mock
    private MongoOperations mongoOperations;
    @Mock
    private NormalizedTransactionRepository normalizedTransactionRepository;
    @Mock
    private AccountingUniverseRepository accountingUniverseRepository;

    private AddressPoisoningDetector detector;

    private Set<String> wallets;
    private Set<String> fullFingerprints;
    private Set<String> suffixes;
    private Set<String> prefixes;

    @BeforeEach
    void setUp() {
        detector = new AddressPoisoningDetector(
                mongoOperations,
                normalizedTransactionRepository,
                accountingUniverseRepository
        );
        lenient().when(normalizedTransactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        wallets = Set.of(WALLET_A.toLowerCase(), WALLET_B.toLowerCase());
        fullFingerprints = Set.of(
                AddressPoisoningDetector.fingerprint(WALLET_A),
                AddressPoisoningDetector.fingerprint(WALLET_B)
        );
        suffixes = Set.of(
                AddressPoisoningDetector.suffix(WALLET_A),
                AddressPoisoningDetector.suffix(WALLET_B)
        );
        prefixes = Set.of(
                AddressPoisoningDetector.prefix(WALLET_A),
                AddressPoisoningDetector.prefix(WALLET_B)
        );
    }

    @Test
    @DisplayName("1 wei from vanity-matching address (full fingerprint) is detected")
    void detectsVanityPoisoningDust() {
        String vanityAddress = "0xf03bd4b57fdf6db5f8385b57c9c9fad906c13021";
        NormalizedTransaction tx = externalTransferIn("0xdfb6863b", WALLET_B, NetworkId.ARBITRUM,
                "ETH", "0.000000000000000001", vanityAddress);
        assertThat(detected(tx)).isTrue();
    }

    @Test
    @DisplayName("F2a: suffix-only match (cp prefix differs) at 1 wei is poisoning")
    void suffixOnlyMatchAtOneWei() {
        // Cp suffix "693f" matches WALLET_A, but prefix "1a80" != "1a87"
        String suffixOnly = "0x1a80000000000000000000000000000000aa693f";
        NormalizedTransaction tx = externalTransferIn("0x15ea6c22", WALLET_A, NetworkId.ARBITRUM,
                "ETH", "0.000000000000000001", suffixOnly);
        assertThat(detected(tx)).isTrue();
    }

    @Test
    @DisplayName("F2a: suffix-only match for wallet B at 1 wei is poisoning")
    void suffixOnlyMatchWalletB() {
        // Suffix "3021" matches WALLET_B, prefix "f030" != "f03b"
        String suffixOnly = "0xf030000000000000000000000000000000003021";
        NormalizedTransaction tx = externalTransferIn("0x955c3259", WALLET_B, NetworkId.ARBITRUM,
                "ETH", "0.000000000000000001", suffixOnly);
        assertThat(detected(tx)).isTrue();
    }

    @Test
    @DisplayName("F2b: full fingerprint match at 1 gwei (above old threshold) is detected")
    void fullFingerprintAtOneGwei() {
        String vanityAddress = "0xf03b000000000000000000000000000000003021";
        NormalizedTransaction tx = externalTransferIn("0x813890c3", WALLET_B, NetworkId.ARBITRUM,
                "ETH", "0.000000001", vanityAddress);
        assertThat(detected(tx)).isTrue();
    }

    @Test
    @DisplayName("F2b: full fingerprint match above 1 gwei is NOT poisoning")
    void fullFingerprintAboveGweiNotPoisoning() {
        String vanityAddress = "0xf03b000000000000000000000000000000003021";
        NormalizedTransaction tx = externalTransferIn("0xtest01", WALLET_B, NetworkId.ARBITRUM,
                "ETH", "0.01", vanityAddress);
        assertThat(detected(tx)).isFalse();
    }

    @Test
    @DisplayName("F2c: known scam-EOA reverse-dust at 1 wei is poisoning")
    void scamEoaReverseDust() {
        String scamEoa = "0x2ea8492134eea72a26423ed65a2b4c5c9d11ac6d";
        Set<String> scamEoas = Set.of(scamEoa.toLowerCase());
        Set<String> scamSuffixes = Set.of(AddressPoisoningDetector.suffix(scamEoa));

        NormalizedTransaction tx = externalTransferIn("0xfccd6e80", WALLET_A, NetworkId.ARBITRUM,
                "ETH", "0.000000000000000001", scamEoa);

        boolean result = detector.isPoisoningDust(tx, wallets, fullFingerprints, suffixes, prefixes,
                scamEoas, scamSuffixes);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("F2a (scammer suffix): cp suffix matches known scammer suffix at 1 wei")
    void scammerSuffixMatch() {
        String scamEoa = "0x2ea8492134eea72a26423ed65a2b4c5c9d11ac6d";
        Set<String> scamEoas = Set.of(scamEoa.toLowerCase());
        Set<String> scamSuffixes = Set.of(AddressPoisoningDetector.suffix(scamEoa));

        // Different address but suffix "ac6d" matches the scam EOA suffix
        String cpWithScamSuffix = "0x2ea0000000000000000000000000000000aac6d";
        NormalizedTransaction tx = externalTransferIn("0xcce37c54", WALLET_A, NetworkId.ARBITRUM,
                "ETH", "0.000000000000000001", cpWithScamSuffix);

        boolean result = detector.isPoisoningDust(tx, wallets, fullFingerprints, suffixes, prefixes,
                scamEoas, scamSuffixes);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("F2d: generic 1-wei dust from random non-protocol EOA is poisoning")
    void genericOneWeiDust() {
        String randomEoa = "0x9dca000000000000000000000000000000008dde";
        NormalizedTransaction tx = externalTransferIn("0x793edaf5", WALLET_A, NetworkId.ARBITRUM,
                "ETH", "0.000000000000000001", randomEoa);
        assertThat(detected(tx)).isTrue();
    }

    @Test
    @DisplayName("transfer from actual tracked wallet is NOT poisoning")
    void transferFromTrackedWalletIsNotPoisoning() {
        NormalizedTransaction tx = externalTransferIn("0xabc123", WALLET_A, NetworkId.ARBITRUM,
                "ETH", "0.000000000000000001", WALLET_B);
        assertThat(detected(tx)).isFalse();
    }

    @Test
    @DisplayName("larger-than-dust amount from non-fingerprint address is NOT poisoning")
    void largerAmountNotPoisoning() {
        String randomAddress = "0xaaaa000000000000000000000000000000bbbb00";
        NormalizedTransaction tx = externalTransferIn("0xdef456", WALLET_B, NetworkId.ARBITRUM,
                "ETH", "0.01", randomAddress);
        assertThat(detected(tx)).isFalse();
    }

    @Test
    @DisplayName("address with non-matching fingerprint above 1 wei is NOT poisoning")
    void nonMatchingFingerprintNotPoisoning() {
        String randomAddress = "0xaaaa000000000000000000000000000000bbbb00";
        NormalizedTransaction tx = externalTransferIn("0xghi789", WALLET_A, NetworkId.ARBITRUM,
                "ETH", "0.001", randomAddress);
        assertThat(detected(tx)).isFalse();
    }

    @Test
    @DisplayName("fingerprint extracts first 4 + last 4 hex chars")
    void fingerprintExtraction() {
        assertThat(AddressPoisoningDetector.fingerprint(WALLET_A)).isEqualTo("1a87:693f");
        assertThat(AddressPoisoningDetector.fingerprint(WALLET_B)).isEqualTo("f03b:3021");
        assertThat(AddressPoisoningDetector.fingerprint(null)).isNull();
        assertThat(AddressPoisoningDetector.fingerprint("0x1234")).isNull();
    }

    @Test
    @DisplayName("suffix extracts last 4 hex chars")
    void suffixExtraction() {
        assertThat(AddressPoisoningDetector.suffix(WALLET_A)).isEqualTo("693f");
        assertThat(AddressPoisoningDetector.suffix(WALLET_B)).isEqualTo("3021");
        assertThat(AddressPoisoningDetector.suffix(null)).isNull();
    }

    @Test
    @DisplayName("prefix extracts first 4 hex chars after 0x")
    void prefixExtraction() {
        assertThat(AddressPoisoningDetector.prefix(WALLET_A)).isEqualTo("1a87");
        assertThat(AddressPoisoningDetector.prefix(WALLET_B)).isEqualTo("f03b");
        assertThat(AddressPoisoningDetector.prefix(null)).isNull();
    }

    @Test
    @DisplayName("full batch detection excludes poisoning dust and saves")
    void fullBatchDetectsAndExcludes() {
        AccountingUniverse universe = new AccountingUniverse();
        AccountingUniverse.Member member = new AccountingUniverse.Member();
        member.setRef(WALLET_B);
        member.setType(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        universe.setMembers(List.of(member));
        when(accountingUniverseRepository.findAll()).thenReturn(List.of(universe));

        String vanityAddress = "0xf03bd4b57fdf6db5f8385b57c9c9fad906c13021";
        NormalizedTransaction tx = externalTransferIn("0xdfb6863b", WALLET_B, NetworkId.ARBITRUM,
                "ETH", "0.000000000000000001", vanityAddress);
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of())      // loadKnownScamCounterparties — no scam txns
                .thenReturn(List.of(tx));    // loadCandidates

        int excluded = detector.detectAndExclude(25);
        assertThat(excluded).isEqualTo(1);
        assertThat(tx.getExcludedFromAccounting()).isTrue();
        assertThat(tx.getAccountingExclusionReason()).isEqualTo("ADDRESS_POISONING_DUST");
        assertThat(tx.getMissingDataReasons()).contains("ADDRESS_POISONING_DUST");
        verify(normalizedTransactionRepository).saveAll(any());
    }

    @Test
    @DisplayName("re-running detector does not duplicate stamps (idempotent)")
    void idempotentExecution() {
        AccountingUniverse universe = new AccountingUniverse();
        AccountingUniverse.Member member = new AccountingUniverse.Member();
        member.setRef(WALLET_B);
        member.setType(AccountingUniverse.MemberType.ON_CHAIN_WALLET);
        universe.setMembers(List.of(member));
        when(accountingUniverseRepository.findAll()).thenReturn(List.of(universe));
        when(mongoOperations.find(any(Query.class), eq(NormalizedTransaction.class)))
                .thenReturn(List.of());

        int excluded = detector.detectAndExclude(25);
        assertThat(excluded).isZero();
        verify(normalizedTransactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("SF-2: outbound transfer to a vanity look-alike of a real counterparty is excluded")
    void mirroredOutboundVanityIsExcluded() {
        String realCp = "0x6aea6b72aaaaaaaaaaaaaaaaaaaaaaaaaaaa63c6";
        String spoofCp = "0x6aeaffffffffffffffffffffffffffffffff63c6"; // same 6aea:63c6 fingerprint, different middle
        Set<String> realCounterparties = Set.of(realCp.toLowerCase());
        Set<String> realFingerprints = Set.of(AddressPoisoningDetector.fingerprint(realCp));

        NormalizedTransaction tx = externalTransferOut(spoofCp, "0xfakeusdc", "USDC", "-107.315094", null);

        boolean result = detector.isMirroredOutboundSpoof(tx, Set.of(), realCounterparties, realFingerprints);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("SF-2: legitimate outbound to a real counterparty (priced asset) is NOT excluded")
    void legitimateOutboundNotExcluded() {
        String realCp = "0x6aea6b72aaaaaaaaaaaaaaaaaaaaaaaaaaaa63c6";
        Set<String> realCounterparties = Set.of(realCp.toLowerCase());
        Set<String> realFingerprints = Set.of(AddressPoisoningDetector.fingerprint(realCp));

        // Exact real counterparty with a priced leg -> not a poison look-alike.
        NormalizedTransaction tx = externalTransferOut(realCp, "0xrealusdc", "USDC", "-107.315094", new BigDecimal("1.00"));

        boolean result = detector.isMirroredOutboundSpoof(tx, Set.of(), realCounterparties, realFingerprints);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("SF-2: outbound whose counterparty fingerprint matches nothing real is NOT excluded")
    void unrelatedOutboundNotExcluded() {
        String realCp = "0x6aea6b72aaaaaaaaaaaaaaaaaaaaaaaaaaaa63c6";
        Set<String> realCounterparties = Set.of(realCp.toLowerCase());
        Set<String> realFingerprints = Set.of(AddressPoisoningDetector.fingerprint(realCp));

        String unrelated = "0x1111000000000000000000000000000000002222";
        NormalizedTransaction tx = externalTransferOut(unrelated, "0xfakeusdc", "USDC", "-5", null);

        boolean result = detector.isMirroredOutboundSpoof(tx, Set.of(), realCounterparties, realFingerprints);
        assertThat(result).isFalse();
    }

    private static NormalizedTransaction externalTransferOut(
            String counterparty, String assetContract, String assetSymbol, String quantity, BigDecimal unitPriceUsd
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(counterparty);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setNetworkId(NetworkId.BASE);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_OUT);
        tx.setCounterpartyAddress(counterparty);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.SELL);
        flow.setAssetContract(assetContract);
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setUnitPriceUsd(unitPriceUsd);
        flow.setCounterpartyAddress(counterparty);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        tx.setMissingDataReasons(new ArrayList<>());
        return tx;
    }

    private boolean detected(NormalizedTransaction tx) {
        return detector.isPoisoningDust(tx, wallets, fullFingerprints, suffixes, prefixes,
                Set.of(), Set.of());
    }

    private static NormalizedTransaction externalTransferIn(
            String id, String wallet, NetworkId networkId,
            String assetSymbol, String quantity, String counterparty
    ) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setTxHash(id);
        tx.setWalletAddress(wallet);
        tx.setSource(NormalizedTransactionSource.ON_CHAIN);
        tx.setNetworkId(networkId);
        tx.setType(NormalizedTransactionType.EXTERNAL_TRANSFER_IN);
        tx.setBlockTimestamp(Instant.parse("2026-01-15T10:00:00Z"));
        tx.setCounterpartyAddress(counterparty);
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setAssetSymbol(assetSymbol);
        flow.setQuantityDelta(new BigDecimal(quantity));
        flow.setRole(NormalizedLegRole.BUY);
        flow.setCounterpartyAddress(counterparty);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        tx.setMissingDataReasons(new ArrayList<>());
        return tx;
    }
}
