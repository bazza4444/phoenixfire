# 🔥 Phoenix Fire — Complete Setup Guide
## Get Your APK Built Automatically — No Software Needed

---

## OVERVIEW

Once this is set up (takes ~15 minutes), your workflow forever after is:

> **Edit app_list.json on GitHub website → Save → APK automatically built → Download it**

That's it. No software. No coding. Just a web browser.

---

## PART 1 — CREATE YOUR FREE GITHUB ACCOUNT

1. Go to **https://github.com**
2. Click **Sign up** (top right)
3. Enter your email, create a password, choose a username
4. Verify your email
5. Choose the **Free** plan

---

## PART 2 — CREATE YOUR REPOSITORY (your app's home)

1. Once logged in, click the **+** button (top right) → **New repository**
2. Fill in:
   - **Repository name:** `PhoenixFire`
   - **Description:** `Phoenix Fire Firestick Installer`
   - Set to **Private** (so your app list is just yours)
   - ✅ Tick **Add a README file**
3. Click **Create repository**

---

## PART 3 — UPLOAD THE PROJECT FILES

1. On your new repository page, click **Add file → Upload files**
2. Drag the entire contents of the `PhoenixFire` folder (from the ZIP I gave you) into the upload box
   - Include: `app/`, `.github/`, `build.gradle`, `settings.gradle`, `gradle.properties`, `gradlew`, `gradle/`
3. At the bottom, type a commit message: `Initial Phoenix Fire upload`
4. Click **Commit changes**

---

## PART 4 — CREATE YOUR SIGNING KEY (one time only)

Your APK needs a digital signature so Android phones will install it.
You need to generate one key — this takes 2 minutes.

### Option A — If you have a PC/Mac with Java installed:
1. Open Terminal (Mac) or Command Prompt (Windows)
2. Run the `generate_keystore.sh` script:
   ```
   bash generate_keystore.sh
   ```
3. It will print 4 values for you to copy — see Part 5

### Option B — Use an online keystore generator (no software needed):
1. Go to: **https://keystore-generator.netlify.app** (or search "online Android keystore generator")
2. Fill in any name/details (these are just for the certificate)
3. Download the keystore file
4. Run this command to get the base64 value (needed for GitHub):
   ```
   base64 -w 0 your-keystore.jks
   ```

### Your 4 secret values will be:
| Secret Name | Value |
|---|---|
| `SIGNING_KEY` | The long base64 string from your keystore file |
| `KEY_ALIAS` | `phoenixfire` (or whatever alias you chose) |
| `KEY_STORE_PASSWORD` | Your keystore password |
| `KEY_PASSWORD` | Your key password |

---

## PART 5 — ADD SECRETS TO GITHUB

This stores your signing key safely inside GitHub (never visible to anyone).

1. Go to your repository on GitHub
2. Click **Settings** (top menu of the repo)
3. In the left sidebar: **Secrets and variables → Actions**
4. Click **New repository secret** for each of the 4 values:

   **Secret 1:**
   - Name: `SIGNING_KEY`
   - Value: *(paste the long base64 string)*
   - Click **Add secret**

   **Secret 2:**
   - Name: `KEY_ALIAS`
   - Value: `phoenixfire`
   - Click **Add secret**

   **Secret 3:**
   - Name: `KEY_STORE_PASSWORD`
   - Value: `PhoenixFire2024!` *(or your chosen password)*
   - Click **Add secret**

   **Secret 4:**
   - Name: `KEY_PASSWORD`
   - Value: `PhoenixFire2024!` *(or your chosen password)*
   - Click **Add secret**

---

## PART 6 — TRIGGER YOUR FIRST BUILD

1. Go to your repository → click **Actions** tab
2. You'll see **🔥 Build Phoenix Fire APK** in the left list
3. Click it → click **Run workflow** → click the green **Run workflow** button
4. Watch the build run (takes 3-5 minutes)
5. When it goes green ✅ — your APK is ready!

---

## PART 7 — DOWNLOAD YOUR APK

After the build succeeds:

1. Click **Releases** on the right side of your repo homepage
   — OR go to the Actions tab → click the finished run → scroll to **Artifacts**
2. Download `PhoenixFire-v20240329-1234.apk`
3. Transfer to your Android phone (AirDrop, email, Google Drive, USB)
4. On your phone: open the file → tap **Install**
   *(If prompted about Unknown Sources — tap Settings → allow it)*
5. Open **Phoenix Fire** 🔥

---

## PART 8 — HOW TO ADD/CHANGE APPS (your ongoing workflow)

This is what you'll do whenever you want to update the app:

1. Go to your GitHub repository
2. Navigate to: `app → src → main → assets → app_list.json`
3. Click the **pencil icon ✏️** (Edit this file)
4. Add, edit, or remove apps. Each app looks like:

```json
{
  "name": "Kodi",
  "version": "21.0",
  "description": "Open source media player and entertainment hub",
  "icon_url": "",
  "apk_url": "https://mirrors.kodi.tv/releases/android/arm64-v8a/kodi-21.0-Omega-arm64-v8a.apk",
  "package_name": "org.xbmc.kodi",
  "category": "Media Players"
}
```

5. Click **Commit changes** (green button, bottom of page)
6. Go to **Actions** tab — your new APK is already building! ⚙️
7. Download the new APK from **Releases** in ~4 minutes

---

## FIRESTICK SETUP (users do this once)

Tell your users:

1. On Firestick: **Settings → My Fire TV → Developer Options**
2. Turn ON **ADB Debugging**
3. Turn ON **Apps from Unknown Sources**
4. Make sure phone and Firestick are on **same WiFi**

Then Phoenix Fire handles everything automatically.

---

## TROUBLESHOOTING

**Build fails with "SIGNING_KEY not found"**
→ Double-check you added all 4 secrets in Part 5 with exact names (case-sensitive)

**Build fails with Gradle error**
→ Send me the error from the Actions log and I'll fix it

**App installed but can't find Firestick**
→ Make sure ADB Debugging is ON on the Firestick (see Firestick Setup above)

**APK won't install on phone**
→ Go to phone Settings → Apps → Special access → Install unknown apps → allow your browser/file manager

---

## SENDING ME UPDATES

When you want me to update the app (new features, new design, etc):

1. Go to your repo → find the file you want to share
2. Copy the contents and paste to me in chat
3. I'll update the code and give you back the changed files
4. Upload them to GitHub → new APK builds automatically

---

*Phoenix Fire 🔥 — Built for you, runs forever*
