package kyle.poultry.finance.api.model;

/**
 * How money moved. A starting set; extend when the business names more methods. It is stored on every cash ledger
 * row and used by the stock, delivery and report modules to say or break down how money was paid.
 */
public enum PaymentMethod { CASH, GCASH, BANK_TRANSFER, OTHER }
