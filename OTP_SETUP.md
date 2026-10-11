# Free OTP login (SMS goes out from your own phone's SIM)

App -> Apps Script `sendOtp` -> "SMS Gateway for Android" app on YOUR phone -> SMS to the user.
Correct OTP = that device becomes the ONLY active device for the number (others log out within ~1 minute).

## One-time setup
1. Use an Android phone (5.0+) with a working SIM that can send SMS. Keep it switched on, with internet.
2. Install the app: github.com/capcom6/android-sms-gateway/releases -> download the latest APK -> install.
3. Open it, allow the SMS permission (if Android blocks it: App info -> menu -> "Allow restricted settings").
4. Turn on "Cloud Server" and tap "Online". Note the Username and Password it shows.
5. Apps Script -> Project Settings -> Script Properties -> add:
   SMSGATE_USER = that username
   SMSGATE_PASS = that password
6. Paste the new backend/Code.gs, then Deploy -> Manage deployments -> pencil -> New version -> Deploy.
7. Test: in Apps Script put your own number in testSmsGateway(), select it in the function list, Run.
   The SMS should arrive in a few seconds.
8. Upload the app files, let Actions build the APK, install it, log in.

## Limits / notes
- Costs nothing extra, but it uses that SIM's SMS allowance. Most SIMs cap around 100 SMS/day, so the
  backend allows at most 30 OTPs per ~6 hours (OTP_MAX_GLOBAL_PER_6H in Code.gs). Raise it only if your SIM allows more.
- If that phone is off / offline / out of SMS balance, nobody can get an OTP. Keep it charged and connected.
- Each number: 60 s between OTPs, 5 per hour. OTP valid 10 min, 5 wrong tries, one-time use.
- The old PIN login is switched off on the server (it would let anyone skip the OTP).
