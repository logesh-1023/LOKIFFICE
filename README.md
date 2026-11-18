# LOKIFFICE v6.1 — Calendar-Month Masked Spend Dashboard & Full-Screen Offline AI Assistant

## What's New in v6.1
1. **Calendar-Month Debit Filtering (`scanCurrentMonthInbox`):** Queries SMS strictly from the **1st of the current calendar month (`00:00:00`)** to today—so if it's the 10th of the month, only those 10 days are tallied (never spilling into the prior month).
2. **Bank-Style Privacy Eye Toggle (`toggleSpendMask`):** Masks all rupee figures as `₹XXXX` by default on screen until you tap the **Eye icon** on the Spend card.
3. **AI-Powered Vendor Classification (No Generic "Other" Bucket):** Extracts exact vendor/hotel/restaurant/UPI names and uses local `SmolLM2-360M-Instruct` (`CLASSIFY_VENDORS`) plus domain-specific rules (**Food & Dining**, **Commute & Fuel**, **Groceries & Bills**, **Shopping & Retail**, **UPI Merchant / Transfer**, **Direct Card / Bank Debit**) so every spend is explicitly categorized.
4. **Dedicated Floating Bot Assistant & Chart Drawer (`#botModal`):** Preserves the inline AI card on the home screen while adding a floating bot FAB button that opens a full-screen chat assistant wired to live SMS counts, hotel/food visit frequencies, live office weather APIs, 3D parking coordinates, and inline visual spend bar charts.
