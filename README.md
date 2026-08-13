# AutoDialer — Setup Guide

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
