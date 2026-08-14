# AutoDialer — Setup Guide

## Is version me kya naya hai (v4 — redesign + Call Sheets)

- **Naya UI**: purple gradient "hero card" (session progress, bada number), fixed bottom dock (Start/Pause/Skip/Stop hamesha reachable), top-right "Sheets" aur "Dashboard" icons, transaction-list-style rows (avatar circle + pill badge)
- **Call Sheets (naya feature)**: har din ka call record automatically ek alag "sheet" me save hota hai (jaise Excel). Top pe day-picker se koi bhi purana din dekh sakte ho
  - Har row delete kar sakte ho (✕ button, confirmation ke saath)
  - Pura din ka sheet bhi delete kar sakte ho ("Delete This Sheet", confirmation ke saath)
- Naye files: `CallLogStore.kt`, `CallLogActivity.kt`, `CallLogAdapter.kt`, `activity_call_log.xml`, `item_call_log.xml`

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

## Zaruri baat (dhyan dena)

- Ye app sirf outgoing calls ke liye hai jahan **aap khud baat karte ho** — ye robocall ya recorded message nahi bhejta
- Sirf un logon ko call karo jinhone khud apna number diya ho (jaise job applicants) — random/purchased number lists pe bulk calling India me TRAI regulations ke against ja sakti hai
- Agar aap bahut zyada numbers rozana call kar rahe ho, apne telecom operator se confirm kar lena ki koi bulk-calling registration to nahi chahiye
