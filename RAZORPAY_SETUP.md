# Razorpay in-app payment (PhonePe / GPay / Paytm directly, no QR)

Flow: app -> Apps Script `razorpayCreateOrder` (price decided on the server, order saved in the Orders tab)
-> Razorpay Checkout (UPI apps list) -> Apps Script `razorpayVerify` asks Razorpay if the order is paid -> plan activates.
Safety net: if the app never confirms, `check` reports `hasUnclaimed` and the app calls `razorpaySync`.

## One-time setup
1. Apps Script: paste backend/Code.gs. Project Settings -> Script Properties:
   RAZORPAY_KEY_ID = live key id, RAZORPAY_KEY_SECRET = live secret (never put the secret in the app).
2. Deploy -> Manage deployments -> pencil -> New version -> Deploy (same Web App URL stays).
   The tabs Orders and TrialDevices are created automatically.
3. Prices: Code.gs RAZORPAY_PLANS (server), SubscriptionManager.kt + activity_subscription.xml (app text), pricing.html +
   netlify/functions/create-order.js (website). Currently Rs 9 / 299 / 999.
4. Push the project to GitHub -> Actions builds the APK.

## Test
Pay the Rs 9 plan with PhonePe/GPay: the plan should activate right after paying. Check Razorpay -> Transactions,
and the Orders tab (Status = activated) and Activations tab (new row with order_...).
