# MYtrack Android

MYtrack is an Android live-location sharing MVP. Both people must agree to share and use the same private pair code.

## Included
- Google Maps screen and current-device GPS marker
- Pair-code entry
- Start/stop sharing control
- Supabase location upsert and partner polling every 5 seconds while the app is open
- Android runtime location permission
- GitHub Actions workflow to build a debug APK

## Setup required before live two-phone testing
1. Create a **separate Supabase project** for MYtrack.
2. In Supabase SQL Editor, run `supabase/schema.sql`.
3. Copy the project URL and anon/public key.
4. In `app/build.gradle.kts`, replace `YOUR_PROJECT` and `YOUR_SUPABASE_ANON_KEY`.
5. Create a Google Maps Platform API key with Maps SDK for Android enabled, then replace `REPLACE_WITH_GOOGLE_MAPS_API_KEY` in `app/src/main/AndroidManifest.xml`.
6. Push to `main`. GitHub Actions builds a debug APK and uploads artifact `MYtrack-debug-APK`.

## Test with two phones
1. Install the same debug APK on both phones and allow location permission.
2. Enter the same private pair code (at least 6 characters) on both phones and tap **Simpan kode**.
3. Tap **Mulai berbagi** on both phones.
4. Each phone should see the other phone's marker after the next refresh cycle.
5. Tap **Hentikan** to stop sharing.

## MVP limitations and safety
- Sharing runs only while the app is open; background tracking and foreground-service notification are not included yet.
- The SQL policies currently allow anonymous clients to read and write location rows across the test table. Use only a disposable test Supabase project and dummy test data; do not use real locations until authenticated pair-membership RLS or a secured Edge Function has replaced these policies.
- No hidden tracking, stealth mode, or access to another person's location without consent.
- A Google Maps API key and a separate Supabase project are required for the live map and location sync.
