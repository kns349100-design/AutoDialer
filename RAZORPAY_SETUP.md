# Razorpay in-app payment (PhonePe / GPay / Paytm directly, no QR)

Flow: app -> Netlify `/api/create-order` -> Razorpay Checkout SDK (UPI apps list) ->
Apps Script `razorpayVerify` asks Razorpay if the order is paid -> plan activates.

## Do this once
1. Apps Script (backend/Code.gs): paste the updated file, then Project Settings -> Script Properties:
   - `RAZORPAY_KEY_ID`     = live key id
   - `RAZORPAY_KEY_SECRET` = live key secret (never put it in the app)
2. Deploy -> Manage deployments -> pencil -> New version -> Deploy (same Web App URL stays).
3. Netlify site `callconnect-app` must already have the create-order function + env vars (done for the website).
4. Push this project to GitHub -> Actions builds the APK.

## Test
Pay the Rs 10 plan with PhonePe/GPay. Plan should activate right after payment.
Check Razorpay Dashboard -> Transactions -> Payments, and the `Activations` sheet (new row with order_...).

Old SMS/UPI-VPA auto-verification was removed (READ_SMS no longer needed).
