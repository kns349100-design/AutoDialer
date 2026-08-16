# Firebase Phone Login Setup (zaroori — iske bina login/OTP kaam nahi karega)

Ye ek baar setup karna hai, phir hamesha kaam karega (jab tak google-services.json valid rahe).

## Step 1 - Firebase Project banao
1. https://console.firebase.google.com kholo
2. **Add project** -> naam do "AutoDialer" -> Create

## Step 2 - Android app add karo
1. Project dashboard me **Android icon (</> ke pass)** pe click karo "Add app"
2. Android package name me EXACTLY ye likhna: `com.autodialer.app`
3. App nickname: "AutoDialer" (optional)
4. **Register app**

## Step 3 - google-services.json download karo
1. Firebase "Download google-services.json" button dega - download karo
2. Ye file apne AutoDialer project ke andar `app/google-services.json` path pe **replace** karo (existing placeholder file ki jagah)
   - GitHub pe: repo me `app/google-services.json` file kholo, pencil (edit) icon se uska content select-all-delete karo, downloaded file ka content paste karo, commit karo
   - Ya: "Upload files" se seedha upload kar do usi path pe (GitHub replace kar dega)

## Step 4 - Phone Authentication ON karo
1. Firebase console me left menu -> **Build -> Authentication**
2. **Get started**
3. **Sign-in method** tab -> **Phone** pe click karo -> **Enable** toggle ON karo -> Save

## Step 5 - SHA-1 fingerprint add karo (better reliability ke liye)
GitHub Actions build khud SHA-1 nikal ke deta hai, isliye keytool install karne ki zarurat nahi:

1. Repo me code upload/commit karo aur "Actions" -> "Run workflow" chalao (jaisa normal build karte ho)
2. Build complete hone ke baad, us run ke andar **"build"** job pe click karo
3. Steps ki list me **"Print debug keystore SHA-1"** step expand karo
4. Wahan ek line dikhegi jaisi: `SHA1: AB:CD:EF:12:34:...`
5. Ye poori SHA1 value copy karo
6. Firebase console me: **Project settings (gear icon)** -> apni Android app ke neeche **"Add fingerprint"** -> paste karo -> Save
7. Firebase se naya `google-services.json` phir se download karo (isme ab SHA1 bhi shamil hoga) aur Step 3 jaisa dobara replace karo

## Step 6 - Rebuild karo
GitHub pe naya code (with real google-services.json) upload/commit karo, Actions se build chalao, naya APK download karo.

## Test kaise karo
App khologe to sabse pehle **Login screen** aayegi. Apna 10-digit number daalo, "Send OTP" dabao, SMS me aaya OTP daalo, "Verify OTP" dabao - andar chale jaoge.

## Agar kaam na kare
- Error "Login service abhi setup nahi hua" dikhe -> matlab abhi bhi placeholder google-services.json use ho rahi hai, Step 3 dobara check karo
- OTP SMS na aaye -> Firebase console me Authentication -> Sign-in method -> Phone enabled hai ya nahi check karo, aur SHA-1 add kiya hai ya nahi
- Firebase free tier me roz ek limited number of OTP free hote hain (kaafi zyada hote hain normal use ke liye, 1000 logon ke liye bhi generally free tier sufficient hai, lekin agar kabhi limit hit ho to Firebase console billing section me dikh jayega)
