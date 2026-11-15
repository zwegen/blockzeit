package de.blockzeit;

import android.content.Context;
import de.blockzeit.core.MarketData;
import de.blockzeit.core.PriceChange;

/** One cached PRICE presentation shared by the widget and chart header; never fetches. */
final class PriceDisplay {
    final String price, title;
    final PriceChange change;
    private PriceDisplay(Store.PriceSample quote, String currency, Double reference) {
        price = quote.text;
        title = price + " " + MarketData.symbol(currency);
        change = PriceChange.between(quote.current, reference);
    }
    static PriceDisplay read(Context context, String currency, long now) {
        return new PriceDisplay(Store.priceSample(context, currency, now), currency,
                PriceHistoryCache.reference(context, currency, now));
    }
}
