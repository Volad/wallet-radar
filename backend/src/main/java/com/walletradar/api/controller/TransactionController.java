package com.walletradar.api.controller;

import com.walletradar.api.dto.TransactionHistoryResponse;
import com.walletradar.costbasis.query.HistoryPage;
import com.walletradar.costbasis.query.TransactionHistoryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transaction history API sourced from confirmed normalized transactions.
 */
@RestController
@RequestMapping("/api/v1/assets")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionHistoryQueryService transactionHistoryQueryService;

    @GetMapping("/{assetId}/transactions")
    public ResponseEntity<TransactionHistoryResponse> getTransactions(
            @PathVariable String assetId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "DESC") String direction
    ) {
        HistoryPage page = transactionHistoryQueryService.findByAsset(assetId, cursor, limit, direction);
        return ResponseEntity.ok(new TransactionHistoryResponse(
                page.items().stream()
                        .map(item -> new com.walletradar.api.dto.TransactionHistoryItemResponse(
                                item.eventId(),
                                item.txHash(),
                                item.networkId(),
                                item.walletAddress(),
                                item.blockTimestamp(),
                                item.eventType(),
                                item.assetSymbol(),
                                item.assetContract(),
                                item.quantityDelta(),
                                item.priceUsd(),
                                item.priceSource(),
                                item.totalValueUsd(),
                                item.realisedPnlUsd(),
                                item.avcoAtTimeOfSale(),
                                item.status(),
                                item.hasOverride()
                        ))
                        .toList(),
                page.nextCursor(),
                page.hasMore()
        ));
    }
}
