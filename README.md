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


## Radar AIS kapal (tahap server)
- Edge Function: `supabase/functions/ais-radar/index.ts`.
- `supabase/config.toml` requires a valid Supabase Auth JWT (`verify_jwt = true`); the AIS provider key is never shipped in the Android APK.
- The function accepts `lat`, `lon`, and optional `radius` (5–50 km), then opens a short AISStream WebSocket collection window and returns recent position reports.
- **Not live yet:** this repository's Android app does not yet sign users in or call the radar function. Do not disable JWT verification to make it appear to work.

### Configure and deploy the protected AIS function
1. In Supabase Dashboard, open the MYtrack project and copy its Project Reference.
2. Create a Supabase Personal Access Token in your Supabase account.
3. In GitHub repository Settings → Secrets and variables → Actions, add:
   - `SUPABASE_ACCESS_TOKEN` = your Supabase personal access token
   - `SUPABASE_PROJECT_REF` = the MYtrack project reference
   - `AISSTREAM_API_KEY` = the AISStream key (already added as a GitHub secret)
4. Run GitHub Actions workflow **Deploy MYtrack AIS Radar** manually.
5. After deployment, the Android app still needs Supabase Auth login/session integration before it can call this JWT-protected function.

Never put provider keys or Supabase service-role keys in Android source, APK assets, or public repository files. Use test data until authenticated pair-membership RLS is implemented for location sharing.
