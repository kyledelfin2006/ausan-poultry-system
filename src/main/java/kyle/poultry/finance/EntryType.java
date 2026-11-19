package kyle.poultry.finance;

/**
 * What a ledger row means, and therefore its direction. It is the vocabulary of the finance module and is used by
 * the other modules and the report module when they ask for totals; the type, never a negative amount, tells
 * whether a row is revenue, cost or cash.
 *
 * <p>Amounts are always positive; the type fixes the direction.
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

    /** Whether a row moves money into the business, out of it, or not at all (revenue and cost are not cash). */
    public enum CashDirection { IN, OUT, NONE }

    private final CashDirection cash;

    /**
     * Creates a type with its fixed cash direction.
     *
     * @param cash whether this kind of row moves cash in, out or not at all
     */
    EntryType(CashDirection cash) { this.cash = cash; }

    /** {@return whether rows of this type move cash in, out or not at all} */
    public CashDirection cashDirection() { return cash; }
}
