# Ausan Poultry System: business problem and proposed system

**Document status:** First-release proposal. Business requirements are based on the supplied draft; implementation details can be refined as development begins.

**Business:** Ausan Poultry Farm  
**Application:** Ausan Poultry System  
**Coverage:** Two branches (Ausan is a trading business: it buys stock from suppliers and resells it through deliveries)

## Business problem

Ausan Poultry Farm records some stock, delivery, and financial activity in paper logs, notepads, calculators, and direct communication. These separate records make it difficult to know current stock, trace deliveries, and see how sales and expenses affect available funds.

> Ausan Poultry Farm needs a shared system for inventory, deliveries, and financial records. A paid delivery should create a stock movement and a financial record from the same transaction, while preserving a history that the owner and authorized staff can review.

## Current situation

| Area | Current way of working | Problem to solve |
| --- | --- | --- |
| Inventory | Staff record stock received, used, or sent out in separate records. Example items include nipples, cages, water jugs, and chickens. | Current balances and the reasons for changes are hard to verify. |
| Deliveries | Staff use delivery records and direct updates from drivers. A delivery starts at one of two branches and goes to a destination supplied by the user. | Items, driver, route, payment, dates, and status are not easy to review together. |
| Finance | Sales, stock purchases, and other expenses rely on paper records and manual calculations. | Collections, cash movement, and profit are easy to confuse or miscalculate. |

### Repository status

The repository is currently a Spring Boot starter. It has an application entry point, generated starter guidance, and starter test files. Inventory, delivery, finance, PostgreSQL configuration, Docker setup, and the frontend are not yet implemented. This document describes the intended system rather than existing features.

## First-release goals

1. Keep a current stock balance and a dated history of every stock change.
2. Record paid deliveries and connect each one to its stock deduction and sale/payment records.
3. Track a delivery from **Active** to **Done** or **Cancelled**, including a manually entered completion time.
4. Show sales collected, stock purchases, other expenses, cash movement, and profit as distinct figures.
5. Let the owner and authorized staff enter and review records for both branches.

## Proposed system

### Products and stock

- Add and update products, including a name and unit of measure.
- Track available quantity by branch so a delivery deducts stock from its starting branch.
- Record opening stock, purchases, donations, usage, and corrections as dated stock movements. Each movement records its quantity, reason, branch, and the person who entered it.
- Record the amount paid for purchased stock. A donation or opening balance does not automatically imply a cash expense.
- Show current quantities and the movement history that explains each balance.
- Prevent a delivery from taking more available stock than its selected branch holds.

For example, initial quantities could be 50 nipples, 50 cages, 50 water jugs, and 500 chickens. Later receipts of 100 units should appear as separate movements, preserving both the initial and added quantities.

### Deliveries

Each delivery records:

- A unique delivery ID or name, its driver, and its status.
- Products and quantities, with the price or amount charged and a positive delivery total.
- Payment amount and method, because the customer pays before dispatch.
- Starting branch and a destination entered as text.
- Start date and time; completion date and time when staff learn that it arrived.

The first release uses **Active**, **Done**, and **Cancelled**. The owner or authorized staff enter the delivery and update its status after communicating with the driver. A driver app is not required.

#### Delivery workflow

1. Staff select a starting branch, enter the delivery and payment details, and save it as **Active**.
2. Saving the paid Active delivery automatically deducts its items from available stock at that branch and records the sale and collection. The delivery does not have to be Done for its payment to count.
3. When arrival is reported, staff mark it **Done** and enter the completion time.
4. If it is **Cancelled**, the system automatically restores the deducted stock through new reversal movements. **No confirmation that the items physically returned is required for this automatic restoration.** The original delivery and stock-out movements remain in history. A refund, if made, is recorded as a separate financial movement.

Cancellation should also reverse the sale in reporting while retaining the original sale and payment records for audit. If the customer has not yet been refunded, the retained payment must be visible separately from active sales. The business's policy for handling such retained payments should be agreed before finance reports are finalized.

### Finance

- Record each delivery's sale, collected payment, amount, and method with a link to the delivery.
- Record stock purchases and other operating expenses with dates, amounts, and descriptions.
- Record refunds when they occur, linked to the cancelled delivery.
- Let users filter transactions and totals by a chosen period and review the underlying entries.

| Figure | Meaning |
| --- | --- |
| Sales collected | Customer money received for paid deliveries, shown with cancellation and refund effects made clear. |
| Stock purchases | Cash paid to acquire inventory. |
| Other expenses | Operating costs entered by the owner or finance staff. |
| Cash movement | Money received minus money paid out, including refunds. |
| Profit | Sales less the cost of stock delivered and relevant operating expenses. |

The purchase payment and the cost of stock used in a delivery are different figures. A stock purchase is a cash outflow when inventory is acquired; the stock cost belongs in profit when that stock is delivered. Reports must avoid counting the same cost twice. The costing method and the treatment of opening stock need a business decision before an exact profit figure is built.

### Access and operation

The owner and authorized staff can create and update records. Access should reflect their duties, particularly for financial entries and corrections. The first version relies on staff to enter driver updates and completion times. Changes to stock and financial records should remain traceable to their source transaction and user.

## Scope of the first release

| Included | Deferred |
| --- | --- |
| Products; branch stock balances and history; opening stock, purchases, donations, and adjustments | Live GPS and route planning |
| Paid deliveries, automatic stock-out and sale/collection, status changes, cancellation reversals | Separate driver application |
| Purchases, expenses, refunds, and period-based financial views | Payroll and broader business management |
| Owner and authorized staff access | Features not needed for the inventory–delivery–finance workflow |

## Proposed delivery order

1. **Confirm business data and rules:** identify both branches, product units, staff roles, payment methods, and the stock costing and retained-payment policies.
2. **Build the backend foundation:** PostgreSQL persistence, Docker-based local setup, and access control with Spring Boot and Maven.
3. **Add products and stock:** branch balances, receipts, adjustments, purchase costs, and movement history.
4. **Add paid deliveries:** validation, stock deduction, sale and payment recording, status changes, automatic cancellation restoration, and refunds.
5. **Add financial views:** separate sales, collections, purchases, expenses, cash movement, and profit calculations for selected periods.
6. **Build the frontend:** React, Vite, and TypeScript screens for stock, deliveries, finance, and staff workflows.

## Acceptance criteria for the first release

- A staff member can enter an opening balance and later stock receipts, then explain the displayed balance from the movement history.
- A paid Active delivery with a positive amount deducts stock from its starting branch and creates linked sale and payment records when saved.
- Marking a delivery Done records its completion time without creating another sale or stock deduction.
- Cancelling a delivery automatically creates stock-restoration movements, retains the original history, and records a refund only when a refund is made.
- Finance views distinguish stock purchase cash outflow from the cost of goods used for profit, and show period totals with their underlying transactions.
