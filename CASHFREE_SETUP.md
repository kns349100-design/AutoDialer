# Cashfree Payment Setup (real in-app payment, no coupon codes needed)

Isse users "Pay" button dabate hi Cashfree ka payment page khulega, wahan UPI/card/netbanking
se pay karenge, "I've Paid" dabate hi automatic unlock ho jayega.

Apna App ID aur Secret Key Cashfree Dashboard se milega (Developers -> API Keys). In dono ko
kabhi bhi is repo me, code me, ya kisi aur public jagah paste mat karna - sirf neeche diye
step ke through Apps Script "Script Properties" me daalna (wo private rehta hai).

Ye dono sirf **backend (Apps Script)** me jaate hain — app (APK) ke andar kabhi nahi, kyunki
Secret Key leak hone se koi bhi fake payment mark kar sakta hai.

## Step 1 - Keys backend me daalo
1. Apna Google Sheet kholo jisme Apps Script attach hai (`backend/SETUP.md` wala)
2. Extensions -> Apps Script
3. Left menu me gear icon (Project Settings)
4. Neeche **Script Properties** section me ye 3 properties add karo:
   - Property: `CASHFREE_APP_ID` — Value: apni Cashfree App ID (Test ya Live)
   - Property: `CASHFREE_SECRET_KEY` — Value: apni Cashfree Secret Key (Test ya Live)
   - Property: `CASHFREE_ENV` — Value: `TEST` (ya `PROD` jab Live keys use karo)
5. Save karo
6. Deploy -> Manage deployments -> pencil icon -> New version -> Deploy (taaki naya code active ho)

## Step 2 - Test karo (Test Mode keys ke saath)
1. App build karo (SCRIPT_URL set hona chahiye, `backend/SETUP.md` dekho)
2. "Plan" screen me koi bhi "Pay" button dabao — browser me Cashfree ka payment page khulega
3. Test Mode me Cashfree ke test UPI/card se payment kar sakte ho (Cashfree docs me sandbox
   test payment details milte hain)
4. Payment ho jaane ke baad app me wapas aao, **"I've Paid — Check Status"** button dabao —
   plan automatic activate ho jayega, koi code nahi chahiye

## Live (real paisa) pe jaane ke liye
1. Cashfree Dashboard pe KYC complete karo (PAN, bank account verify)
2. Live Mode me jaake naye **App ID** aur **Secret Key** generate karo
   (Cashfree Dashboard -> Developers -> API Keys -> Production)
3. Step 1 dobara karo, bas ab:
   - `CASHFREE_APP_ID` — apni Live App ID
   - `CASHFREE_SECRET_KEY` — apni Live Secret Key
   - `CASHFREE_ENV` — `PROD`
4. Ab real UPI/card/netbanking se paisa seedha aapke bank account me aayega
   (Cashfree apna settlement cycle follow karega — dashboard me dikhega)

## Agar galti se koi key kahi expose ho jaye (GitHub, chat, screenshot, etc.)
Turant Cashfree Dashboard -> Developers -> API Keys pe jaake us key ko **Reset** kar do -
purani key turant invalid ho jayegi, koi risk nahi rahega. Naya Secret Key firse Script
Properties me update karke Deploy karna mat bhoolna.
