# 🔥 Phoenix Fire — Firestick App Installer

A branded Android app that lets you send APKs directly to an Amazon Fire TV / Firestick
over your WiFi network using ADB (Android Debug Bridge).

---

## 📱 How It Works

1. User opens Phoenix Fire on their Android phone
2. Taps **Find My Firestick** — app scans the local WiFi for Fire TV devices
3. Taps their Firestick in the list to connect
4. Picks an app from your curated list
5. Taps **Send to Firestick** — app downloads & installs it automatically

---

## 🛠 HOW TO BUILD (one-time setup)

### Requirements
- A PC or Mac with **Android Studio** installed (free: https://developer.android.com/studio)
- Java 8 or above (included with Android Studio)

### Steps
1. Open Android Studio
2. Click **Open** and select the `PhoenixFire` folder
3. Wait for Gradle sync to complete (downloads dependencies automatically)
4. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. The APK appears in: `app/build/outputs/apk/debug/app-debug.apk`
6. Transfer to an Android phone and install!

---

## ✏️ HOW TO ADD YOUR OWN APPS

Edit this one file:
```
app/src/main/assets/app_list.json
```

Each app entry looks like this:
```json
{
  "name": "My App Name",
  "version": "1.0",
  "description": "Short description shown to users",
  "icon_url": "",
  "apk_url": "https://your-server.com/myapp.apk",
  "package_name": "com.example.myapp",
  "category": "My Apps"
}
```

### Field guide:
| Field | Description |
|-------|-------------|
| `name` | App name shown in list |
| `version` | Version string (display only) |
| `description` | Short description |
| `icon_url` | URL to icon image (leave blank for default flame icon) |
| `apk_url` | **Direct download link to the .apk file** — must be publicly accessible |
| `package_name` | Android package name (e.g. `org.xbmc.kodi`) |
| `category` | Groups apps by category label |

### Where to host APKs
- Your own web server / VPS
- Google Drive (get direct download link via: `https://drive.google.com/uc?export=download&id=FILE_ID`)
- Dropbox (change `?dl=0` to `?dl=1` at end of share URL)
- GitHub Releases

---

## 📺 FIRESTICK SETUP (users must do this once)

On the Firestick:
1. **Settings → My Fire TV → Developer Options**
2. Turn ON **ADB Debugging**
3. Turn ON **Apps from Unknown Sources**
4. Both the phone and Firestick must be on the **same WiFi network**

---

## 🔄 OUR WORKFLOW

1. You edit `app_list.json` with your apps
2. Send it back to Claude
3. Claude updates the project and gives you back a ZIP
4. You open in Android Studio → Build APK → Test!

---

## 🔧 TECHNICAL NOTES

### ADB Library
The app uses **dadb** (`dev.mobile:dadb:1.2.7`) — a pure Java ADB implementation.
This means NO root required, NO adb binary bundled — it speaks the ADB wire protocol directly.

The dadb integration point in `AdbManager.java`:
```java
dadb.Dadb adb = dadb.Dadb.create(deviceIp, devicePort);
adb.install(apkFile);
adb.close();
```
This replaces the simulation loop currently in the file.

### Project Structure
```
PhoenixFire/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   └── app_list.json          ← EDIT THIS to add your apps
│   │   ├── java/com/phoenixfire/installer/
│   │   │   ├── MainActivity.java
│   │   │   ├── DeviceScanActivity.java
│   │   │   ├── AppDetailActivity.java
│   │   │   ├── AppAdapter.java
│   │   │   ├── DeviceAdapter.java
│   │   │   ├── AdbManager.java
│   │   │   ├── AppListParser.java
│   │   │   ├── AppModel.java
│   │   │   ├── FirestickDevice.java
│   │   │   └── DownloadService.java
│   │   └── res/
│   │       ├── layout/                ← UI layouts
│   │       ├── drawable/              ← Icons
│   │       └── values/                ← Colors, strings, themes
│   └── build.gradle                   ← Dependencies
└── README.md
```

---

## 🎨 Branding
- App name: **Phoenix Fire**
- Package: `com.phoenixfire.installer`
- Theme: Dark background (#1A1A1A) with fire orange (#FF6B00) and red (#CC2200) accents
- To change the name, edit `app/src/main/res/values/strings.xml`
- To change colors, edit `app/src/main/res/values/colors.xml`

---

*Built with ❤️ by Phoenix Fire*
