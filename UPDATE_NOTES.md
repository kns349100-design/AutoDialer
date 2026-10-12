# What changed in this version

- Razorpay in-app payment (UPI apps list, no QR); price set by the server; paid orders always get activated (see PAYMENT_AND_TRIAL_NOTES.md)
- Prices: Rs 9 / 12 hours, Rs 299 / month, Rs 999 / year
- Free 24 h trial: once per number AND per device, resumes after reinstall, clock-rollback proof
- Login: phone number + OTP (free: SMS sent from your own phone via SMS Gateway for Android) - see OTP_SETUP.md.
  One number = one device. Old PIN login is switched off on the server.
- Speed/robustness: call log + session saved on a background thread with an in-memory cache, atomic file writes,
  corrupted saved data can no longer crash the app, number list parsing and big file import no longer freeze the screen,
  OCR/import results are never lost, a call that could not be placed is put back to Pending.
- GitHub Actions: removed the broken Android-SDK setup step (runner already has the SDK).
- READ_SMS permission and the SMS payment verifier were removed (no longer needed).

Setup order: 1) Code.gs -> Apps Script + Script Properties (Razorpay keys, SMSGATE_USER/PASS) + New version deploy
2) upload the project to GitHub 3) wait for the green build, install the APK.
