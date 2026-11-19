package kyle.poultry.finance;

/**
 * What a ledger row means. Amounts are always positive; the type fixes the direction.
 * <pre>
 * SALE, SALE_REVERSAL           revenue recognised / cancelled   (no cash)
 * COST_OF_GOODS, COGS_REVERSAL  cost of stock delivered / cancelled (no cash)
 * PAYMENT_IN                    customer money received          (cash in)
 * REFUND                        money given back                 (cash out)
 * STOCK_PURCHASE                cash paid for inventory          (cash out)
 * EXPENSE                       operating cost                   (cash out)
 * </pre>
 */
public enum EntryType {
    SALE(CashDirection.NONE), COST_OF_GOODS(CashDirection.NONE),
    PAYMENT_IN(CashDirection.IN),
    REFUND(CashDirection.OUT), STOCK_PURCHASE(CashDirection.OUT), EXPENSE(CashDirection.OUT),
    SALE_REVERSAL(CashDirection.NONE), COGS_REVERSAL(CashDirection.NONE);

    public enum CashDirection { IN, OUT, NONE }

    private final CashDirection cash;

    EntryType(CashDirection cash) { this.cash = cash; }

    public CashDirection cashDirection() { return cash; }
}
