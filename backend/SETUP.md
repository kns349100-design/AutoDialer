# License Backend Setup (5-10 minutes, free, no hosting cost)

## Step 1 - Google Sheet banao
1. sheets.google.com pe jao, naya blank sheet banao
2. Naam do: "AutoDialer Licenses"
3. Neeche 3 tabs banao (bottom-left "+" se):
   - Tab 1 naam: `Codes`
   - Tab 2 naam: `Activations`
   - Tab 3 naam: `Users`

## Step 2 - "Codes" tab me headers aur data daalo
Row 1 (headers), phir apne 4 codes:

| Code | Type | MaxUses | UsedCount | Active |
|---|---|---|---|---|
| SAPDEAL-FREE | FREE | 0 | 0 | TRUE |
| SAPDEAL-150 | MONTHLY | 0 | 0 | TRUE |
| SAPDEAL-100 | MONTHLY | 0 | 0 | TRUE |
| SAPDEAL-10 | HOURLY12 | 0 | 0 | TRUE |

(MaxUses = 0 ka matlab unlimited baar use ho sakta hai. Agar chaho ki SAPDEAL-FREE sirf 5 logon ko milna chahiye, to MaxUses me 5 daal do.)

## Step 3 - "Activations" tab me sirf headers daalo
Row 1:

| DeviceId | Code | ActivatedAt | ExpiryAt | PlanType | Revoked |
|---|---|---|---|---|---|

(Baaki rows khud-ba-khud bharegi jab log codes redeem karenge - inhe manually edit mat karna, sirf kisi ek device ko band karna ho to uski row ke "Revoked" column me TRUE likh dena.)

## Step 3.5 - "Users" tab me sirf headers daalo
Row 1 (ye login system ke liye hai - phone number + PIN, koi SMS/billing nahi lagti):

| Phone | PinHash | ActiveDeviceId | UpdatedAt |
|---|---|---|---|

(Baaki rows khud-ba-khud bharegi jab log pehli baar login karke apna PIN set karenge. Kisi ek number ko manually logout/reset karna ho to uski row delete kar do - agli baar wo number naya PIN set kar payega.)

## Step 4 - Apps Script attach karo
1. Sheet ke top menu me **Extensions -> Apps Script**
2. Jo default code khula hai use pura select karke delete karo
3. Is repo ki `backend/Code.gs` file ka poora content copy-paste karo
4. Top-left "Untitled project" ko rename karo: "AutoDialer License Backend"
5. **Save** (disk icon ya Ctrl+S)

## Step 5 - Deploy karo (Web App banao)
1. Top-right **Deploy -> New deployment**
2. Gear icon (⚙️) pe click karke type select karo: **Web app**
3. Settings:
   - Description: "AutoDialer License API"
   - Execute as: **Me**
   - Who has access: **Anyone**
4. **Deploy** dabao
5. Google permission maangega - apna Google account authorize karo (ek warning aayegi "unsafe app" jaisi, wo normal hai apne khud ke script ke liye - "Advanced" -> "Go to project (unsafe)" -> Allow)
6. Deploy hone ke baad ek **Web app URL** milega jaisa:
   `https://script.google.com/macros/s/AKfycb.../exec`
7. **Ye URL copy kar lo** - yehi Android app me daalna hai

## Step 6 - Android app me URL daalo
`app/src/main/java/com/autodialer/app/SubscriptionManager.kt` file kholo, top me ye line dhundo:

```kotlin
const val SCRIPT_URL = "PASTE_YOUR_APPS_SCRIPT_URL_HERE"
```

Isko apne Step 5 wale URL se replace karo, phir GitHub pe upload/commit/build karo jaisa pehle karte aaye ho.

## Ek device ko revoke (band) kaise karo
1. Google Sheet kholo, "Activations" tab
2. Us device ki row dhundo (DeviceId ya Code se pehchano)
3. Uske "Revoked" column me `TRUE` likh do
4. Bas - agli baar jab wo app internet pe check karega, uska access khatam ho jayega

## Naya code add karna ho (jaise kisi branch ke liye)
"Codes" tab me bas ek nayi row add kar do (Code, Type=FREE/HOURLY12/MONTHLY/YEARLY, MaxUses, UsedCount=0, Active=TRUE) - koi app rebuild nahi karni padegi.
