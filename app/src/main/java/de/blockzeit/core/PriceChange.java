package de.blockzeit.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Signed local comparison; direction and text share the same rounded value. */
public final class PriceChange {
    public final String text;
    public final int direction;
    private PriceChange(String text,int direction) { this.text=text;this.direction=direction; }
    public static PriceChange unknown() { return new PriceChange("—",0); }
    public static PriceChange between(Double current,Double reference) {
        if(current==null||reference==null||!Double.isFinite(current)||!Double.isFinite(reference)
                ||current<=0||reference<=0||!Double.isFinite((current/reference-1)*100))return unknown();
        BigDecimal first=BigDecimal.valueOf(reference);
        BigDecimal rounded=BigDecimal.valueOf(current).subtract(first).multiply(BigDecimal.valueOf(100))
                .divide(first,2,RoundingMode.HALF_UP);
        int sign=rounded.signum();
        return new PriceChange((sign<0 ? "−" : "+")+rounded.abs().toPlainString()+" %",sign);
    }
}
