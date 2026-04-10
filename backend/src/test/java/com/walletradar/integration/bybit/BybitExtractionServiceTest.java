package com.walletradar.integration.bybit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.bybit.BybitExtractedEvent;
import com.walletradar.domain.transaction.bybit.BybitExtractedEventStatus;
import com.walletradar.domain.transaction.integration.IntegrationRawEvent;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BybitExtractionServiceTest {

    private final BybitExtractionService service = new BybitExtractionService(new ObjectMapper());

    @Test
    void executionHistoryCreatesBaseAndQuoteSwapLegs() {
        IntegrationRawEvent rawEvent = rawEvent(
                "integration-1",
                BybitIntegrationStream.EXECUTION_LINEAR,
                "exec-1",
                """
                        {
                          "symbol":"ETHUSDT",
                          "side":"Buy",
                          "execQty":"0.5",
                          "execValue":"1000",
                          "execPrice":"2000",
                          "execFee":"0.0005",
                          "feeCurrency":"ETH",
                          "execTime":"1760000000000"
                        }
                        """
        );

        List<BybitExtractedEvent> events = service.extract(rawEvent);

        assertThat(events).hasSize(2);
        BybitExtractedEvent base = events.stream().filter(event -> "ETH".equals(event.getAssetSymbol())).findFirst().orElseThrow();
        BybitExtractedEvent quote = events.stream().filter(event -> "USDT".equals(event.getAssetSymbol())).findFirst().orElseThrow();

        assertThat(base.getCanonicalType()).isEqualTo("SWAP");
        assertThat(base.getSourceFileType()).isEqualTo("uta_derivatives");
        assertThat(base.getQuantityRaw()).isEqualByComparingTo("0.5");
        assertThat(base.getFeePaid()).isEqualByComparingTo("-0.0005");
        assertThat(base.getChange()).isEqualByComparingTo("0.4995");
        assertThat(base.getFilledPrice()).isEqualByComparingTo("2000");
        assertThat(base.getStatus()).isEqualTo(BybitExtractedEventStatus.RAW);

        assertThat(quote.getCanonicalType()).isEqualTo("SWAP");
        assertThat(quote.getQuantityRaw()).isEqualByComparingTo("-1000");
        assertThat(quote.getFeePaid()).isNull();
        assertThat(quote.getChange()).isEqualByComparingTo("-1000");
    }

    @Test
    void onChainDepositMapsToExternalInboundWithNetwork() {
        IntegrationRawEvent rawEvent = rawEvent(
                "integration-1",
                BybitIntegrationStream.DEPOSIT_ONCHAIN,
                "deposit-1",
                """
                        {
                          "coin":"USDC",
                          "amount":"123.45",
                          "chain":"ARBI",
                          "txID":"0xabc",
                          "toAddress":"0xwallet",
                          "status":"success",
                          "successAt":"1760000000000"
                        }
                        """
        );

        List<BybitExtractedEvent> events = service.extract(rawEvent);

        assertThat(events).hasSize(1);
        BybitExtractedEvent event = events.get(0);
        assertThat(event.getCanonicalType()).isEqualTo("EXTERNAL_INBOUND");
        assertThat(event.getSourceFileType()).isEqualTo("withdraw_deposit");
        assertThat(event.getAssetSymbol()).isEqualTo("USDC");
        assertThat(event.getQuantityRaw()).isEqualByComparingTo("123.45");
        assertThat(event.getNetworkId()).isEqualTo(NetworkId.ARBITRUM);
        assertThat(event.getTxHash()).isEqualTo("0xabc");
        assertThat(event.getReceivedAddress()).isEqualTo("0xwallet");
    }

    @Test
    void flexibleSavingRedeemBecomesVaultWithdraw() {
        IntegrationRawEvent rawEvent = rawEvent(
                "integration-1",
                BybitIntegrationStream.EARN_FLEXIBLE_SAVING,
                "earn-1",
                """
                        {
                          "orderType":"Redeem",
                          "orderValue":"250",
                          "coin":"USDT",
                          "status":"SUCCESS",
                          "updatedTime":"1760000000000"
                        }
                        """
        );

        List<BybitExtractedEvent> events = service.extract(rawEvent);

        assertThat(events).hasSize(1);
        BybitExtractedEvent event = events.get(0);
        assertThat(event.getCanonicalType()).isEqualTo("VAULT_WITHDRAW");
        assertThat(event.getBybitType()).isEqualTo("Earn");
        assertThat(event.getQuantityRaw()).isEqualByComparingTo("250");
        assertThat(event.getAssetSymbol()).isEqualTo("USDT");
    }

    @Test
    void fundingHistoryEarnInterestBecomesRewardClaim() {
        IntegrationRawEvent rawEvent = rawEvent(
                "integration-1",
                BybitIntegrationStream.FUNDING_HISTORY,
                "funding-1",
                """
                        {
                          "memberId":"33625378",
                          "currency":"USDT",
                          "ioDirection":"I",
                          "txnAmt":"0.0608",
                          "afterAmt":"1000.0608",
                          "createTime":"1759993805",
                          "showBusiTypeEn":"Earn",
                          "descriptionEn":"Easy Earn | Flexible Interest Distribution"
                        }
                        """
        );

        List<BybitExtractedEvent> events = service.extract(rawEvent);

        assertThat(events).hasSize(1);
        BybitExtractedEvent event = events.get(0);
        assertThat(event.getSourceFileType()).isEqualTo("fund_asset_changes");
        assertThat(event.getBybitType()).isEqualTo("Earn");
        assertThat(event.getBybitDescription()).isEqualTo("Flexible Savings Interest Distribution");
        assertThat(event.getCanonicalType()).isEqualTo("REWARD_CLAIM");
        assertThat(event.getQuantityRaw()).isEqualByComparingTo("0.0608");
        assertThat(event.getWalletBalance()).isEqualByComparingTo("1000.0608");
        assertThat(event.getTimeUtc()).isEqualTo(Instant.ofEpochSecond(1759993805L));
    }

    @Test
    void fundingHistoryTradingBotTransferMapsToInternalTransfer() {
        IntegrationRawEvent rawEvent = rawEvent(
                "integration-1",
                BybitIntegrationStream.FUNDING_HISTORY,
                "funding-2",
                """
                        {
                          "memberId":"33625378",
                          "currency":"USDT",
                          "ioDirection":"O",
                          "txnAmt":"25",
                          "afterAmt":"75",
                          "createTime":"1759993805",
                          "showBusiTypeEn":"Transfer",
                          "descriptionEn":"Transfer to Trading Bot"
                        }
                        """
        );

        List<BybitExtractedEvent> events = service.extract(rawEvent);

        assertThat(events).hasSize(1);
        BybitExtractedEvent event = events.get(0);
        assertThat(event.getBybitType()).isEqualTo("Bot");
        assertThat(event.getBybitDescription()).isEqualTo("Transfer to Trading Bot");
        assertThat(event.getCanonicalType()).isEqualTo("INTERNAL_TRANSFER");
        assertThat(event.getQuantityRaw()).isEqualByComparingTo("-25");
    }

    @Test
    void fundingHistoryRepayInterestMapsToFee() {
        IntegrationRawEvent rawEvent = rawEvent(
                "integration-1",
                BybitIntegrationStream.FUNDING_HISTORY,
                "funding-3",
                """
                        {
                          "memberId":"33625378",
                          "currency":"MNT",
                          "ioDirection":"O",
                          "txnAmt":"1.021",
                          "afterAmt":"0",
                          "createTime":"1759993805",
                          "showBusiTypeEn":"Loans",
                          "descriptionEn":"Repay Interest"
                        }
                        """
        );

        List<BybitExtractedEvent> events = service.extract(rawEvent);

        assertThat(events).hasSize(1);
        BybitExtractedEvent event = events.get(0);
        assertThat(event.getBybitType()).isEqualTo("Loans");
        assertThat(event.getBybitDescription()).isEqualTo("Repay Interest");
        assertThat(event.getCanonicalType()).isEqualTo("FEE");
        assertThat(event.getQuantityRaw()).isEqualByComparingTo("-1.021");
        assertThat(event.getBasisRelevant()).isFalse();
    }

    @Test
    void transactionLogCurrencyBuyBecomesSwapConvertLeg() {
        IntegrationRawEvent rawEvent = rawEvent(
                "integration-1",
                BybitIntegrationStream.TRANSACTION_LOG,
                "tx-log-convert-1",
                """
                        {
                          "id":"10419130513445426949046272336253781",
                          "currency":"ETH",
                          "type":"CURRENCY_BUY",
                          "side":"None",
                          "qty":"0",
                          "tradePrice":"0",
                          "change":"0.70215876",
                          "transactionTime":"1744891736725"
                        }
                        """
        );

        List<BybitExtractedEvent> events = service.extract(rawEvent);

        assertThat(events).hasSize(1);
        BybitExtractedEvent event = events.getFirst();
        assertThat(event.getSourceFileType()).isEqualTo("uta_derivatives");
        assertThat(event.getCanonicalType()).isEqualTo("SWAP");
        assertThat(event.getBybitType()).isEqualTo("CURRENCY_BUY");
        assertThat(event.getBybitDescription()).isEqualTo("Currency convert");
        assertThat(event.getAssetSymbol()).isEqualTo("ETH");
        assertThat(event.getQuantityRaw()).isEqualByComparingTo("0.70215876");
        assertThat(event.getBasisRelevant()).isTrue();
    }

    @Test
    void fundingHistoryOnChainEarnSubscriptionInEthFamilyBecomesStakingDeposit() {
        IntegrationRawEvent rawEvent = rawEvent(
                "integration-1",
                BybitIntegrationStream.FUNDING_HISTORY,
                "funding-cmeth-1",
                """
                        {
                          "memberId":"33625378",
                          "currency":"CMETH",
                          "ioDirection":"I",
                          "txnAmt":"0.10687862",
                          "afterAmt":"0.10687862",
                          "createTime":"1745862746",
                          "showBusiTypeEn":"Earn",
                          "descriptionEn":"On-chain Earn subscription"
                        }
                        """
        );

        List<BybitExtractedEvent> events = service.extract(rawEvent);

        assertThat(events).hasSize(1);
        BybitExtractedEvent event = events.get(0);
        assertThat(event.getBybitType()).isEqualTo("Earn");
        assertThat(event.getBybitDescription()).isEqualTo("On-chain Earn subscription");
        assertThat(event.getCanonicalType()).isEqualTo("STAKING_DEPOSIT");
        assertThat(event.getQuantityRaw()).isEqualByComparingTo("0.10687862");
        assertThat(event.getBasisRelevant()).isTrue();
    }

    @Test
    void fundingHistoryEth20StakeBecomesStakingDeposit() {
        IntegrationRawEvent rawEvent = rawEvent(
                "integration-1",
                BybitIntegrationStream.FUNDING_HISTORY,
                "funding-eth20-stake-1",
                """
                        {
                          "memberId":"33625378",
                          "currency":"ETH",
                          "ioDirection":"O",
                          "txnAmt":"0.709",
                          "afterAmt":"0",
                          "createTime":"1741810116",
                          "showBusiTypeEn":"ETH 2.0",
                          "descriptionEn":"Stake"
                        }
                        """
        );

        List<BybitExtractedEvent> events = service.extract(rawEvent);

        assertThat(events).hasSize(1);
        BybitExtractedEvent event = events.get(0);
        assertThat(event.getBybitType()).isEqualTo("ETH 2.0");
        assertThat(event.getBybitDescription()).isEqualTo("Stake");
        assertThat(event.getCanonicalType()).isEqualTo("STAKING_DEPOSIT");
        assertThat(event.getQuantityRaw()).isEqualByComparingTo("-0.709");
        assertThat(event.getBasisRelevant()).isTrue();
    }

    @Test
    void fundingHistoryEth20MintBecomesStakingDeposit() {
        IntegrationRawEvent rawEvent = rawEvent(
                "integration-1",
                BybitIntegrationStream.FUNDING_HISTORY,
                "funding-eth20-mint-1",
                """
                        {
                          "memberId":"33625378",
                          "currency":"METH",
                          "ioDirection":"I",
                          "txnAmt":"0.66865026",
                          "afterAmt":"0.66865026",
                          "createTime":"1741811825",
                          "showBusiTypeEn":"ETH 2.0",
                          "descriptionEn":"Mint"
                        }
                        """
        );

        List<BybitExtractedEvent> events = service.extract(rawEvent);

        assertThat(events).hasSize(1);
        BybitExtractedEvent event = events.get(0);
        assertThat(event.getBybitType()).isEqualTo("ETH 2.0");
        assertThat(event.getBybitDescription()).isEqualTo("Mint");
        assertThat(event.getCanonicalType()).isEqualTo("STAKING_DEPOSIT");
        assertThat(event.getQuantityRaw()).isEqualByComparingTo("0.66865026");
        assertThat(event.getBasisRelevant()).isTrue();
    }

    @Test
    void transactionLogBonusRecollectMapsToNonBasisFee() {
        IntegrationRawEvent rawEvent = rawEvent(
                "integration-1",
                BybitIntegrationStream.TRANSACTION_LOG,
                "txlog-1",
                """
                        {
                          "type":"BONUS_RECOLLECT",
                          "currency":"USDT",
                          "change":"-0.01",
                          "cashFlow":"-0.01",
                          "walletBalance":"99.99",
                          "transactionTime":"1760000000000"
                        }
                        """
        );

        List<BybitExtractedEvent> events = service.extract(rawEvent);

        assertThat(events).hasSize(1);
        BybitExtractedEvent event = events.get(0);
        assertThat(event.getSourceFileType()).isEqualTo("uta_derivatives");
        assertThat(event.getBybitType()).isEqualTo("BONUS_RECOLLECT");
        assertThat(event.getCanonicalType()).isEqualTo("FEE");
        assertThat(event.getAssetSymbol()).isEqualTo("USDT");
        assertThat(event.getQuantityRaw()).isEqualByComparingTo("-0.01");
        assertThat(event.getBasisRelevant()).isFalse();
    }

    private IntegrationRawEvent rawEvent(
            String integrationId,
            BybitIntegrationStream stream,
            String providerEventKey,
            String payloadJson
    ) {
        IntegrationRawEvent rawEvent = new IntegrationRawEvent();
        rawEvent.setId(integrationId + ":" + providerEventKey);
        rawEvent.setSessionId("session-1");
        rawEvent.setIntegrationId(integrationId);
        rawEvent.setProvider("BYBIT");
        rawEvent.setAccountRef("BYBIT:33625378");
        rawEvent.setStream(stream.name());
        rawEvent.setProviderEventKey(providerEventKey);
        rawEvent.setOccurredAt(Instant.parse("2025-10-09T08:53:20Z"));
        rawEvent.setFetchedAt(Instant.parse("2025-10-09T08:54:20Z"));
        rawEvent.setSegmentId("segment-1");
        rawEvent.setPayload(Document.parse(payloadJson));
        rawEvent.setIngestHash(new BigDecimal("1").toPlainString());
        return rawEvent;
    }
}
