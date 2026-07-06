package com.walletradar.ingestion.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings for the on-chain clarification worker.
 */
@ConfigurationProperties(prefix = "walletradar.normalization.clarification")
@NoArgsConstructor
@Getter
@Setter
public class OnChainClarificationProperties {

    private boolean enabled = false;

    private int batchSize = 30;

    private int threads = 2;

    private long leaseSeconds = 300;

    private long retryDelaySeconds = 120;

    private int maxAttempts = 3;

    private FullReceipt fullReceipt = new FullReceipt();
    private RelatedDiscovery relatedDiscovery = new RelatedDiscovery();
    private LiFiStatus liFiStatus = new LiFiStatus();
    private MayanStatus mayanStatus = new MayanStatus();

    public void setFullReceipt(FullReceipt fullReceipt) {
        this.fullReceipt = fullReceipt != null ? fullReceipt : new FullReceipt();
    }

    public void setRelatedDiscovery(RelatedDiscovery relatedDiscovery) {
        this.relatedDiscovery = relatedDiscovery != null ? relatedDiscovery : new RelatedDiscovery();
    }

    public void setLiFiStatus(LiFiStatus liFiStatus) {
        this.liFiStatus = liFiStatus != null ? liFiStatus : new LiFiStatus();
    }

    public void setMayanStatus(MayanStatus mayanStatus) {
        this.mayanStatus = mayanStatus != null ? mayanStatus : new MayanStatus();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getThreads() {
        return threads;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public long getRetryDelaySeconds() {
        return retryDelaySeconds;
    }

    public void setRetryDelaySeconds(long retryDelaySeconds) {
        this.retryDelaySeconds = retryDelaySeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public FullReceipt getFullReceipt() {
        return fullReceipt;
    }

    public RelatedDiscovery getRelatedDiscovery() {
        return relatedDiscovery;
    }

    public LiFiStatus getLiFiStatus() {
        return liFiStatus;
    }

    public MayanStatus getMayanStatus() {
        return mayanStatus;
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class FullReceipt {

        private boolean enabled = false;

        private int batchSize = 50;

        private long retryDelaySeconds = 300;

        private int maxAttempts = 2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getRetryDelaySeconds() {
            return retryDelaySeconds;
        }

        public void setRetryDelaySeconds(long retryDelaySeconds) {
            this.retryDelaySeconds = retryDelaySeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class RelatedDiscovery {

        private boolean enabled = true;

        private long forwardBlockWindow = 750_000L;

        private int maxPages = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getForwardBlockWindow() {
            return forwardBlockWindow;
        }

        public void setForwardBlockWindow(long forwardBlockWindow) {
            this.forwardBlockWindow = forwardBlockWindow;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class LiFiStatus {

        private boolean enabled = true;

        private String baseUrl = "https://li.quest";

        private long timeoutMs = 5000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    @NoArgsConstructor
    @Getter
    @Setter
    public static class MayanStatus {

        private boolean enabled = true;

        private String baseUrl = "https://explorer-api.mayan.finance";

        private long timeoutMs = 5000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
