# AutoDialer — Setup Guide

## Is version me kya naya hai (v4 — redesign + Call Sheets)

- **Naya UI**: purple gradient "hero card" (session progress, bada number), fixed bottom dock (Start/Pause/Skip/Stop hamesha reachable), top-right "Sheets" aur "Dashboard" icons, transaction-list-style rows (avatar circle + pill badge)
- **Call Sheets (naya feature)**: har din ka call record automatically ek alag "sheet" me save hota hai (jaise Excel). Top pe day-picker se koi bhi purana din dekh sakte ho
  - Har row delete kar sakte ho (✕ button, confirmation ke saath)
  - Pura din ka sheet bhi delete kar sakte ho ("Delete This Sheet", confirmation ke saath)
- Naye files: `CallLogStore.kt`, `CallLogActivity.kt`, `CallLogAdapter.kt`, `activity_call_log.xml`, `item_call_log.xml`

## Is version me kya naya hai (v13 — Free Phone+PIN login, Firebase hataya)

- **Firebase poori tarah hata diya gaya hai** — koi Blaze plan, koi billing, koi card add karne ki zarurat nahi
- **Naya free login**: Phone number + 4-digit PIN (SMS nahi bhejta, isliye 100% free)
  - Pehli baar jo PIN daaloge wahi permanently us number ka PIN ban jata hai
  - "Forgot PIN?" se bina purana PIN jaane naya set kar sakte ho
  - **Ek number = ek hi device**: agar same number se doosre phone me login karte ho, pehle wale device pe automatically logout ho jata hai (app har baar resume hone pe check karti hai)
- Same Google Apps Script backend use hota hai jo subscription ke liye hai — **`backend/SETUP.md` me ek naya "Users" tab add karna hoga** (Step 3.5), baaki setup same hai
- `FIREBASE_SETUP.md` file hata di gayi hai (ab zarurat nahi)

## Is version me kya naya hai (v12 — Sheet grouping + manual Collected mark)

- **Call Sheets ab groups me dikhti hai**: Resume sabse upar, phir Positive, phir baaki defaults (Info, No), phir custom options, aur untagged sabse neeche
- **"Mark Collected" button**: sirf Resume-tagged numbers ke aage dikhta hai — jab aap khud WhatsApp check karke confirm kar lo ki reply/resume aa gaya, ek tap se green "✓ Collected" ho jata hai
- ⚠️ Ye **manual hai, automatic nahi** — koi bhi app bina official WhatsApp Business API (jo Meta business verification maangti hai) ke ye khud-ba-khud detect nahi kar sakti ki WhatsApp pe reply aaya ya nahi. Automatic detection ke liye kisi aur ki notifications padhna padega jo privacy-invasive hai — wo nahi banaya

## Is version me kya naya hai (v11 — floating Stop button + Stop-then-Start fix)

- **Floating Stop button**: screen ke top-right corner me chota "⏹ Stop" button hamesha visible rehta hai jab calling chal rahi ho (chahe kahin bhi scroll kiya ho) — turant ruk jati hai tap karte hi
- **Bug fix — Stop ke baad Start**: pehle Stop dabane ke baad "Start" dabane se process resume nahi hota tha. Ab theek se continue hota hai, **jahan se chhoda wahi se** — jin numbers pe pehle hi call ho chuki hai unhe kabhi dobara dial nahi karta (naye unit tests se verify kiya)

## Is version me kya naya hai (v10 — list persistence, keyboard fix, no repeat calls)

- **List ab persist hoti hai**: app band karke dobara kholne pe purani list turant dikhti hai (screen pe hi, tap karne ki zarurat nahi) — sirf tabhi badalti hai jab aap khud naya list load karte ho
- **Keyboard automatically hide hota hai**: jab call khatam hone ke baad outcome-options wali screen aati hai, keyboard nahi dikhta
- **Repeat-call prevention**: agar koi number kabhi bhi pehle call ho chuka hai (kisi bhi din), naya list load karte waqt wo number automatically list se **hata diya jata hai** (sirf warning nahi, seedha exclude) — us number pe dobara call nahi jayegi

## Is version me kya naya hai (v9 — Custom Call Options)

- Numbers section ke neeche **"+ Custom Call Option"** button — apna khud ka text likho (jaise "Callback", "Wrong Number"), color app khud assign kar deta hai
- Custom options bhi **RESUME/NO/POSITIVE/INFO ke saath overlay me automatically dikhte hain** — layout khud adjust hota hai (2 columns me jitne bhi options hon)
- Custom option **Remove** bhi kar sakte ho (same dialog se)
- Purana history/call-log data automatically compatible hai (RESUME/NO/POSITIVE/INFO ke IDs same rakhe hain)

## Is version me kya naya hai (v8 — Direct Payment + Image OCR)

- **Direct in-app payment (UPI, auto-verified)**: "Plan" screen me "Pay Rs 10/300/1000" buttons hain — dabate hi seedha tumhara UPI app (GPay/PhonePe/Paytm) khulta hai, amount pre-filled, koi Cashfree/Razorpay/browser page nahi. Payment hote hi bank ka "credited" SMS aata hai, app khud padh ke turant plan activate kar deta hai - koi code, koi screenshot ya manual step nahi chahiye (SMS permission allow karna zaruri hai iske liye). Agar kisi wajah se SMS na milе, neeche "Send screenshot on WhatsApp" fallback button bhi hai. `CASHFREE_SETUP.md` ab use nahi hoti (purana approach, reference ke liye rakhi hai).
- **Image se numbers import (OCR)**: "Image Upload" button — ek ya kai images select karo (screenshot/photo jisme numbers likhe hain), app khud numbers padh ke nikal leta hai (on-device, free, Google ML Kit), exactly wahi numbers jo image me hain, aur duplicate khud hat jate hain
- Purana coupon-code system bhi maujood hai (backup ke taur pe, agar kabhi manually kisi ko free/discounted access dena ho)

## Is version me kya naya hai (v7 — Phone OTP Login + UI polish)

- **Login screen** ab app khulte hi sabse pehle aata hai — phone number (+91) daalo, OTP aaye, verify karo, tabhi app ke andar jaa sakte ho
- Firebase (Google ki free service) OTP bhejta hai — **zaroori setup: `FIREBASE_SETUP.md` follow karo**, iske bina login kaam nahi karega
- Dashboard aur Call Sheets screens ko bhi gradient header diya (poori app me consistent look)

### Poora setup checklist (naye se shuru karke)
1. `backend/SETUP.md` follow karo (subscription backend, Google Apps Script) → URL milega
2. Wo URL `SubscriptionManager.kt` ki `SCRIPT_URL` line me daalo
3. `FIREBASE_SETUP.md` follow karo (login/OTP backend) → project banao, Phone Auth on karo, `google-services.json` download karke `app/google-services.json` replace karo
4. GitHub pe pehli baar build chalao — Actions log me "Print debug keystore SHA-1" step se SHA1 copy karo, Firebase console me add karo, naya `google-services.json` download karke phir se replace karo
5. Dubara build chalao, APK download karo, phone pe install karo — ab login screen se shuru hoga

## Is version me kya naya hai (v6 — Subscription system)

- **1 din free trial** automatically first launch se shuru
- **Server-side license check** (Google Apps Script backend, free) — codes APK ke andar nahi hain, isliye:
  - Kabhi bhi naya code add kar sakte ho (app rebuild kiye bina)
  - Kisi bhi ek device/branch ka access turant revoke kar sakte ho
- **3 coupon codes** (Google Sheet me edit kar sakte ho): `SAPDEAL-FREE` (lifetime free), `SAPDEAL-150`, `SAPDEAL-100` (dono 1 month access)
- **Monthly (Rs 300) aur Yearly (Rs 1000) plans** — "Plan" screen me dikhte hain
- **Zaroori setup step**: `backend/SETUP.md` follow karke apna free Google Apps Script backend deploy karo (5-10 min), phir uska URL `SubscriptionManager.kt` ki `SCRIPT_URL` line me daalo — **isके bina subscription system kaam nahi karega**

### Important — honest disclaimer
- Chunki payment gateway nahi laga hai, payment aap khud manually (UPI/cash) collect karoge, phir company/admin employee ko code de dega
- Server-side check hone se pehle wale (fully local) system se ye kaafi zyada secure hai, lekin "100% unbreakable" koi bhi software (chahe bank ho) nahi ho sakta — is scale (1000 log) ke liye ye ek reasonable, practical setup hai

## Is version me kya naya hai (v5 — robustness + export)

- **CSV Export**: Call Sheets screen me "Export CSV" button — kisi bhi din ka data CSV file bana ke WhatsApp/Email/Drive se share ho sakta hai, Excel me khulti hai
- **Duplicate-call warning**: Naya list load karte waqt agar koi number pehle (kisi bhi din) call ho chuka hai, warning dikhti hai — dobara call karne se bachne ke liye
- **Crash-safety**: Agar kabhi saved data corrupt ho jaye, app crash nahi karega — us din ka data khali dikhega, baaki app normally chalega

## "Deploy ready" ka matlab yahan

Ye app **functionally stable aur real use ke liye complete** hai (reliable state machine, tested logic, crash recovery, data persistence). Lekin ye ek **sideload APK** hai (GitHub Actions se banaya), Google Play Store pe publish nahi hai — Play Store pe daalne ke liye alag se Google Play Developer account, store listing, aur Play policies (khaaskar phone-permission apps ke liye) follow karni padti hain. Agar wo bhi chahiye to bata dena, alag se guide karunga.


- **File upload** se numbers import (txt/csv, "File Upload" button)
- Har call khatam hote hi **4 full-screen boxes**: RESUME (blue) / NO (red) / POSITIVE (green) / INFO (orange) — tap karte hi turant agla call (koi delay/timer nahi, seedha instant)
- Jo box select kiya uska **chhota colored tag** us number ke aage list me hamesha dikhta hai
- **Batch target**: "kitne calls karne hain" likho (0 = sab) — utne ho jaye to auto-ruk jata hai; dobara "Start" dabao to agle number se continue hota hai (jahan chhoda tha wahi se)
- **Dashboard** (top-right "Dashboard" button): sabhi ho chuki calls + unka tag, aur total Resume/No/Positive/Info count

- **Reliable state machine** (`CallSequencer.kt`) jo guarantee karta hai ki ek call-end event kabhi do baar dial trigger nahi karega, chahe Android duplicate phone-state events bheje
- **Unit tests** (`CallSequencerTest.kt`) jo isi guarantee ko automatically verify karte hain har build pe (GitHub Actions ab `gradle testDebugUnitTest` bhi chalata hai APK banane se pehle)
- **Pause / Resume / Skip / Stop** — sab safe hain (Skip sirf tab kaam karta hai jab call active na ho, kyunki Android normal apps ko ek chal rahi call ko force-end karne ki permission nahi deta)
- **Dashboard**: progress bar, pending/completed/skipped counts
- **Duplicate number detection** list load karte waqt
- **Optional naam** har number ke saath (`Rahul, 9876543210` format me)
- **Delay selector**: 2/3/5/10 second
- **Crash/rotation recovery**: session SharedPreferences me save hota hai; app restart hone par "Resume Session" banner dikhta hai — **lekin auto-call nahi hoti**, aapko manually "Start" dabana padega (safety ke liye)
- **Consent checkbox**: "Start" se pehle confirm karna padta hai ki numbers consenting/authorized hain

## Jaan-boojh kar chhoda gaya (technical ya scope reasons se)

- **Answered / Busy / Rejected / No-Answer detection**: Android normal apps ko outgoing call ka ye status reliably nahi deta — ye sirf "default dialer app" bankar milta hai (bahut bada alag scope + zyada sensitive permissions). Isliye sirf "Completed" track hota hai, fake status nahi dikhaya jata.
- Room database / poori Call History screen, CSV/TXT import-export, contact-book picker, debug-log export screen, full Material 3 redesign — ye sab agle iteration me add ho sakte hain agar chahiye


Ye ek simple Android app hai:
1. Number list paste karo (ek number per line)
2. "List Load Karo" dabao
3. "Start Calling" dabao — pehla number dial hoga
4. Jaise hi call **end** hoti hai, 2 second baad **agla number automatic dial** ho jayega
5. "Stop" dabao kabhi bhi rokne ke liye

## APK kaise banayein — SIRF PHONE SE (laptop nahi chahiye)

Ye poora process phone ke Chrome browser se ho sakta hai, GitHub ki free service use karke:

1. Phone browser me [github.com](https://github.com) kholo, free account banao (agar nahi hai)
2. Top-right "+" icon -> **New repository** -> naam do jaise `AutoDialer` -> **Create repository**
3. Us repo ke andar **"Add file" -> "Upload files"** pe tap karo
4. Is zip ko pehle apne phone me **extract/unzip** karo (koi bhi file manager app ya "RAR/ZIP" app se), phir saari files/folders (`app`, `.github`, `build.gradle`, `settings.gradle`, `gradle.properties`) select karke upload karo
   - **Zaruri**: `.github` folder bhi upload hona chahiye (ye hidden dikh sakta hai, isliye file manager me "show hidden files" on karna)
5. Upload ke baad "Commit changes" dabao
6. Repo ke top menu me **"Actions"** tab pe jao
7. "Build APK" workflow dikhega -> usme tap karo -> **"Run workflow"** button dabao -> phir se "Run workflow" confirm karo
8. 3-5 minute wait karo (GitHub khud APK build karega, tumhare phone pe koi load nahi padega)
9. Build complete hone ke baad us run ke andar "Artifacts" section me **"AutoDialer-apk"** milega -> tap karke download karo (zip file aayegi)
10. Us zip ko extract karo, andar `app-debug.apk` milegi
11. Us APK ko tap karke install karo
    - Phone "Install from unknown sources" allow karne ko kahega — ek baar allow karna hoga (Settings me)

## Phone pe pehli baar kholte waqt

- App **Call** aur **Phone State** permission maangega — dono **Allow** karna zaruri hai, warna app kaam nahi karega
- Kuch phones (Xiaomi, Vivo, Oppo) me "Autostart" / "Battery optimization" band karni pad sakti hai taaki background me call state detect ho sake
- **Plan/Payment screen pe "Read SMS" permission bhi maangega — ye Allow karna zaruri hai**, isi se payment automatic verify hoti hai (bank ka "credited" SMS padh ke turant unlock karta hai, koi code ya screenshot nahi chahiye)

### Install karte waqt "App blocked to protect your device" warning aaye to
Ye warning **Google Play Protect** ki taraf se aati hai kyunki app SMS padhne ki permission maangta hai
(payment verify karne ke liye) — ye normal hai, app safe hai, bas Play Store ke bahar install
hone wale kisi bhi app ko ye sensitive permission ke saath ye warning dikhti hai. Har naye customer
ko ye ek baar dikhegi, unhe ye steps batao:

1. Warning dialog me **"AutoDialer"** wale row pe tap karo (usually "Install anyway" jaisa option
   expand ho jata hai) - agar wo dikhe to "Install anyway" dabao aur aage badho
2. Agar wo option na dikhe: **Settings → Google → Security → Google Play Protect → gear icon (⚙️)
   → "Scan apps with Play Protect"** ko temporarily **OFF** karo, phir APK dobara install karo
3. Install hone ke baad Play Protect ko wapas **ON** kar sakte ho (sirf install ke waqt band karna tha)

## Zaruri baat (dhyan dena)

- Ye app sirf outgoing calls ke liye hai jahan **aap khud baat karte ho** — ye robocall ya recorded message nahi bhejta
- Sirf un logon ko call karo jinhone khud apna number diya ho (jaise job applicants) — random/purchased number lists pe bulk calling India me TRAI regulations ke against ja sakti hai
- Agar aap bahut zyada numbers rozana call kar rahe ho, apne telecom operator se confirm kar lena ki koi bulk-calling registration to nahi chahiye
