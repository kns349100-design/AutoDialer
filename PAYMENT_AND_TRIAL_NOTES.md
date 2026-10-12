# Payment -> access, and the free trial

## Prices (set in 3 places, all now Rs 9 / Rs 299 / Rs 999)
- App screen: activity_subscription.xml + SubscriptionManager.kt
- Server: Code.gs  RAZORPAY_PLANS   (the app only names the plan, the price is decided here)
- Website: pricing.html + netlify/functions/create-order.js
Old-price orders (Rs 10/300/1000) still activate if they get paid, so nobody loses money during the change.

## Why a payment always ends as access
1. Order is created by the server and saved in the "Orders" tab with the device id BEFORE the payment screen opens.
2. After paying, the app asks the server; the server asks Razorpay if it is paid, then activates. The app retries by itself 4 times.
3. If the app dies / no net / data cleared: every normal status check returns hasUnclaimed, the app calls razorpaySync,
   the server re-checks all that device's orders of the last 7 days and activates every paid one. Each order activates once.
4. A new payment is ADDED on top of the remaining time, never replaces it.

## Free trial (24 h)
- One per phone number AND one per device (second SIM/number on the same phone gets none).
- Reinstall / new login with the same number resumes the original trial time - never restarts it.
- Phone clock set backwards does not extend a trial or a plan.

## Tabs created automatically
Orders, TrialDevices  (no manual setup needed)
