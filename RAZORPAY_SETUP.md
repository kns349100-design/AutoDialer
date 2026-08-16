# Razorpay Payment Setup (real in-app payment, no coupon codes needed)

Isse users seedha app ke andar UPI/card se pay karenge aur turant unlock ho jayega.

## Step 1 - Razorpay account banao
1. https://razorpay.com kholo -> **Sign Up**
2. Apna mobile/email se account banao
3. Business type me "Individual/Proprietorship" select kar sakte ho agar company registered nahi hai
4. KYC ke liye chahiye: PAN card, bank account details (jahan paisa aayega)
   - **KYC turant nahi ho to koi baat nahi** - Test Mode me bina KYC ke turant testing shuru kar sakte ho (Step 2 dekho)

## Step 2 - API Keys nikalo
1. Razorpay Dashboard -> left menu **Settings -> API Keys**
2. **Test Mode** me "Generate Test Key" dabao (turant milti hai, KYC ki zarurat nahi)
   - Ye sirf testing ke liye hai - real paisa nahi kategi
3. Do cheezein milengi: **Key Id** (jaise `rzp_test_xxxxx`) aur **Key Secret**
4. Jab KYC complete ho jaye, **Live Mode** me jaake wahi se Live keys generate kar lena (real payment ke liye)

## Step 3 - Key Id Android app me daalo
`app/src/main/java/com/autodialer/app/SubscriptionManager.kt` file me:

```kotlin
const val RAZORPAY_KEY_ID = "PASTE_YOUR_RAZORPAY_KEY_ID_HERE"
```

Isko apni Key Id se replace karo (test wali `rzp_test_...` ya live wali `rzp_live_...`).

IMPORTANT: Key Secret KABHI app me mat daalna - wo sirf backend (Apps Script) me jaata hai, Step 4 dekho.

## Step 4 - Key Secret backend (Apps Script) me daalo
1. Apna Google Sheet kholo jisme Apps Script attach hai (backend/SETUP.md wala)
2. Extensions -> Apps Script
3. Left menu me gear icon (Project Settings)
4. Neeche Script Properties section me Add script property:
   - Property: RAZORPAY_KEY_ID - Value: apni Key Id
   - Property: RAZORPAY_KEY_SECRET - Value: apni Key Secret
5. Save karo
6. Deploy -> Manage deployments -> pencil icon -> New version -> Deploy (taaki naya code active ho)

## Step 5 - Test karo
1. App build karo (SCRIPT_URL aur RAZORPAY_KEY_ID dono set hone chahiye)
2. "Plan" screen me koi bhi "Pay" button dabao
3. Test mode me Razorpay test card/UPI se payment kar sakte ho (Razorpay docs me test card numbers milte hain, jaise card 4111 1111 1111 1111, koi bhi future expiry, koi bhi CVV)
4. Payment success hote hi automatically plan activate ho jayega - koi code nahi chahiye

## Live (real paisa) pe jaane ke liye
1. Razorpay KYC complete karo (PAN, bank account verify)
2. Live Mode se naye Key Id/Secret nikalo
3. Step 3 aur Step 4 dobara karo real live keys ke saath
4. Ab real UPI/card se paisa seedha aapke bank account me aayega
