package com.walletradar.application.costbasis.breakeven;

/**
 * ADR-062 (2026-07-21 amendment) break-even effective-cost offset lane.
 *
 * <ul>
 *   <li>{@link #NET} — realized income (rewards/yield = net − market) reduces effective cost in
 *       addition to trading profit. Default.</li>
 *   <li>{@link #MARKET} — only trading (Market-lane) profit reduces effective cost.</li>
 * </ul>
 *
 * The loss floor keeps effective cost in {@code [0, AVCO]} in both lanes. Read-model only.
 */
public enum OffsetLane {
    NET,
    MARKET
}
