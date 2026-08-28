# Cashfree Payment Setup (real in-app payment, no coupon codes needed)

Isse users "Pay" button dabate hi Cashfree ka Standard Checkout (official hosted payment) page
khulega, wahan UPI/card/netbanking se pay karenge, "I've Paid" dabate hi automatic unlock ho
jayega.

**Zaroori baat jo pehle miss ho gayi thi:** Cashfree ka koi bhi web checkout (Payment Links,
Custom Checkout, ya Standard Checkout - teeno) tabhi kaam karta hai jab jis domain se checkout
page khul raha hai wo Cashfree ke dashboard me **whitelist** ho. Google Apps Script ka apna
domain har deployment pe badal sakta hai, isliye use whitelist nahi kiya ja sakta - isiliye
"not enabled/approved" ya "Broken Link - whitelist your domain" jaisi errors aa rahi thi. Fix:
checkout page ko ek **stable** free domain (GitHub Pages) pe rakho aur wahi domain whitelist
karwao - ek baar ka kaam hai.

Apna App ID aur Secret Key Cashfree Dashboard se milega (Developers -> API Keys). In dono ko
kabhi bhi is repo me, code me, ya kisi aur public jagah paste mat karna - sirf neeche diye
step ke through Apps Script "Script Properties" me daalna (wo private rehta hai).

Ye dono sirf **backend (Apps Script)** me jaate hain — app (APK) ke andar kabhi nahi, kyunki
Secret Key leak hone se koi bhi fake payment mark kar sakta hai.

## Step 1 - GitHub Pages chalu karo (stable checkout page domain)
1. GitHub pe apne AutoDialer repo me jao
2. Repo me `docs/checkout.html` file already hai (is zip me shamil hai) - confirm karo wo
   upload ho chuki hai
3. Repo ke **Settings** tab -> left menu me **Pages**
4. "Build and deployment" -> Source: **Deploy from a branch**
5. Branch: `main` (ya jo bhi tumhari default branch hai), Folder: **/docs** -> **Save**
6. 1-2 minute me GitHub ek URL dikhayega jaise:
   `https://<tumhara-github-username>.github.io/<repo-name>/`
7. Poora checkout page URL hoga: `https://<username>.github.io/<repo-name>/checkout.html`
   (isko copy karke rakh lo, Step 3 me chahiye hoga)

## Step 2 - Cashfree se ye domain whitelist karwao
1. `merchant.cashfree.com` pe login karo
2. **Developers** section me jao, wahan **"Whitelist Domain or App"** option dhundo (ya seedha
   is link pe jao jo Cashfree ki error me diya tha: https://bit.ly/3Xkt3RJ)
3. Apna GitHub Pages domain daalo: `<username>.github.io` (bina `https://` ke, aur bina
   `/checkout.html` ke - sirf domain)
4. Submit karo - Cashfree usually **24 ghante** ke andar approve kar deta hai
5. Jab tak approve nahi hota, "Payment link nahi ban paya" ya "Broken Link" wali error aati
   rahegi - ye normal hai, sirf wait karna hai

## Step 3 - Keys aur naya domain backend me daalo
1. Apna Google Sheet kholo jisme Apps Script attach hai (`backend/SETUP.md` wala)
2. Extensions -> Apps Script
3. Left menu me gear icon (Project Settings)
4. Neeche **Script Properties** section me ye 4 properties add karo:
   - Property: `CASHFREE_APP_ID` — Value: apni Cashfree App ID (Test ya Live)
   - Property: `CASHFREE_SECRET_KEY` — Value: apni Cashfree Secret Key (Test ya Live)
   - Property: `CASHFREE_ENV` — Value: `TEST` (ya `PROD` jab Live keys use karo)
   - Property: `GITHUB_PAGES_URL` — Value: Step 1 ka poora URL, e.g.
     `https://<username>.github.io/<repo-name>/checkout.html`
5. Save karo
6. Deploy -> Manage deployments -> pencil icon -> New version -> Deploy (taaki naya code active ho)

## Step 4 - Test karo (Test Mode keys ke saath, whitelist approve hone ke baad)
1. App build karo (SCRIPT_URL set hona chahiye, `backend/SETUP.md` dekho)
2. "Plan" screen me koi bhi "Pay" button dabao — GitHub Pages wala page khulega, jo turant
   Cashfree ke checkout pe le jayega
3. Test Mode me Cashfree ke test UPI/card se payment kar sakte ho (Cashfree docs me sandbox
   test payment details milte hain)
4. Payment ho jaane ke baad app me wapas aao, **"I've Paid — Check Status"** button dabao —
   plan automatic activate ho jayega, koi code nahi chahiye

## Live (real paisa) pe jaane ke liye
1. Cashfree Dashboard pe KYC complete karo (PAN, bank account verify)
2. Live Mode me jaake naye **App ID** aur **Secret Key** generate karo
   (Cashfree Dashboard -> Developers -> API Keys -> Production)
3. Live mode ke liye bhi wahi GitHub Pages domain dobara whitelist karwana pad sakta hai
   (Test aur Live mode ke whitelist alag ho sakte hain - Cashfree dashboard me check karo)
4. Step 3 dobara karo, bas ab:
   - `CASHFREE_APP_ID` — apni Live App ID
   - `CASHFREE_SECRET_KEY` — apni Live Secret Key
   - `CASHFREE_ENV` — `PROD`
5. Ab real UPI/card/netbanking se paisa seedha aapke bank account me aayega
   (Cashfree apna settlement cycle follow karega — dashboard me dikhega)

## Agar galti se koi key kahi expose ho jaye (GitHub, chat, screenshot, etc.)
Turant Cashfree Dashboard -> Developers -> API Keys pe jaake us key ko **Reset** kar do -
purani key turant invalid ho jayegi, koi risk nahi rahega. Naya Secret Key firse Script
Properties me update karke Deploy karna mat bhoolna.

