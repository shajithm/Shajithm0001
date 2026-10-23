# Expense Tracker (Android)

Track a monthly **forecast budget** by category against your **actual spending**, see the
difference per category and per month, sync everything to the cloud (with offline entry),
and export all data to **Excel (.xlsx)**.

## Features

| Screen | What it does |
|---|---|
| **Budget** | Month selector, summary card (forecast / actual / remaining), one card per category with progress bar and "left" or "over" amount. Tap a category to set its forecast. "Copy forecast from previous month" button when a month is empty. |
| **Expenses** | Every expense for the month grouped by day, with daily totals. Tap to edit or delete. |
| **Year** | 12-month forecast-vs-actual bars, year totals, and per-category totals. Tap a month to open it. |
| **Settings** | Currency (default AED), add/remove categories, export to Excel, sign out. |

**Sync:** data lives in Firebase Cloud Firestore under your account. The app keeps a full
offline copy; anything you add without a connection is saved on the phone immediately and
uploaded automatically when you're back online. A status line shows *All changes synced*,
*Saved on phone — will sync when online*, or *Offline*. Sign in with the same account on
another device to see the same data.

**Excel export** (Share icon, or Settings → Export) creates a workbook with four sheets:
*Budget vs Actual* (month × category with difference and % used, plus month totals),
*Monthly Totals*, *Expenses* (every entry), and *Budgets* (every forecast line). You pick
where to save it (Downloads, Google Drive, etc.).

## Tech

Kotlin · Jetpack Compose (Material 3) · Firebase Auth (email/password) · Cloud Firestore
(offline persistence) · fastexcel for .xlsx. minSdk 26 (Android 8.0), targetSdk 35.

## Setup (one-time, ~10 minutes)

### 1. Create the Firebase project
1. Go to <https://console.firebase.google.com> → **Add project** (Analytics optional).
2. **Add app → Android**. Package name: `com.shajith.expensetracker`. Download
   **google-services.json** and put it in the `app/` folder.
3. **Build → Authentication → Get started → Sign-in method → Email/Password → Enable.**
4. **Build → Firestore Database → Create database** (production mode, a region near you,
   e.g. `me-central1` or `europe-west1`).
5. In Firestore → **Rules**, paste the contents of `firestore.rules` and **Publish**.
   This makes each user's data private to them.

No indexes are needed — all queries are single-field.

### 2a. Build in Android Studio
Open the `ExpenseTracker` folder in Android Studio (Ladybug or newer), let Gradle sync,
then **Run** on a phone or emulator. For an installable file:
**Build → Build App Bundle(s)/APK(s) → Build APK(s)**.

### 2b. Or build on GitHub (no Android Studio needed)
1. This app lives in the `ExpenseTracker/` folder of the repo; its workflow is `.github/workflows/build-expense-tracker.yml`.
2. Repo **Settings → Secrets and variables → Actions → New repository secret**:
   name `GOOGLE_SERVICES_JSON`, value = the full contents of `google-services.json`.
3. **Actions → Build Expense Tracker APK → Run workflow**. When it finishes, download
   **ExpenseTracker-apk** from the run page and install it on your phone
   (allow "Install unknown apps" for your browser/file manager).

## Project layout

```
app/src/main/java/com/shajith/expensetracker/
  App.kt                      Firestore offline cache setup
  MainActivity.kt             Entry point; login vs main UI
  data/Models.kt              Budget, Expense, Prefs, CategoryRow, formatting
  data/ExpenseRepository.kt   Firestore reads (live) and writes
  export/ExcelExporter.kt     .xlsx workbook writer
  ui/AppViewModel.kt          State for month, year, auth, export
  ui/screens/                 Login, main scaffold, tabs, dialogs, settings
  ui/theme/Theme.kt           Material 3 theme
firestore.rules               Security rules to paste into Firebase
.github/workflows/            Cloud APK build
```

## Data model (Firestore)

```
users/{uid}/budgets/{month_category}   { month: "2026-10", category, amount }
users/{uid}/expenses/{id}              { month, date: "2026-10-23", category, amount, note }
users/{uid}/settings/prefs             { currency, categories: [...] }
```
