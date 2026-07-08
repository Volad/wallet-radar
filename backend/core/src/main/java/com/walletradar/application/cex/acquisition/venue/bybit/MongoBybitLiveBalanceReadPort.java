package com.walletradar.application.cex.acquisition.venue.bybit;

import com.walletradar.costbasis.application.port.BybitLiveBalanceReadPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MongoBybitLiveBalanceReadPort implements BybitLiveBalanceReadPort {

    private final MongoOperations mongoOperations;

    @Override
    public List<Row> findAll() {
        return mongoOperations.findAll(BybitLiveBalance.class).stream()
                .map(balance -> new Row(
                        balance.getIntegrationId(),
                        balance.getAssetSymbol(),
                        balance.getFundQty(),
                        balance.getEarnQty(),
                        balance.getUtaQty()
                ))
                .toList();
    }
}
