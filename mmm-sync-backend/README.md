# MMM Sync Backend

This backend receives secure dig updates from AeTweaks and writes them into the Manual Mining Leaderboards Google Sheet.

## What it does

- accepts `POST /mmm/update`
- validates an optional API key
- deduplicates requests using `batchId`
- finds the correct player row by username
- resolves the current server/world column from the in-game name or server host
- increments:
  - `Total Digs`
  - the mapped world/server column

## Requirements

- Java 21
- Gradle
- a Google service account with edit access to the target sheet

## Setup

1. Copy:
   - [service-account.json.example](C:\Users\mult0\Downloads\MiningTrackerTweakerooAddon\mmm-sync-backend\config\service-account.json.example)
   - to `config/service-account.json`
2. Share the Google Sheet with the service account email as an editor
3. Edit [world-map.json](C:\Users\mult0\Downloads\MiningTrackerTweakerooAddon\mmm-sync-backend\config\world-map.json) so each server/world points to the correct numeric digs column
4. Optional environment variables:
   - `MMM_SYNC_PORT`
   - `MMM_SYNC_API_KEY`
   - `MMM_SYNC_SHEET_ID`
   - `MMM_SYNC_SHEET_GID`
   - `MMM_SYNC_PLAYER_COLUMN`
   - `MMM_SYNC_TOTAL_COLUMN`
   - `MMM_SYNC_DATA_START_ROW`
   - `MMM_SYNC_REQUIRE_WORLD_MAPPING`

## Run

```powershell
gradle run
```

## Free hosting

The easiest public setup is to deploy this backend as a Docker web service on a free host such as Koyeb.

Why this backend is ready for that:
- it now honors the platform `PORT` environment variable automatically
- it already exposes a health endpoint at `/health`
- it can read the Google service account from an environment variable instead of a local file

### Recommended deploy shape

1. Push the `mmm-sync-backend` folder to GitHub
2. Create a new web service on your host from that repo/folder
3. Deploy using the included [Dockerfile](C:\Users\mult0\Downloads\MiningTrackerTweakerooAddon\mmm-sync-backend\Dockerfile)
4. Set these environment variables:
   - `MMM_SYNC_SHEET_ID`
   - `MMM_SYNC_SHEET_GID`
   - `MMM_SYNC_PLAYER_COLUMN`
   - `MMM_SYNC_TOTAL_COLUMN`
   - `MMM_SYNC_REQUIRE_WORLD_MAPPING`
   - `MMM_SYNC_GOOGLE_SERVICE_ACCOUNT_JSON`
5. Paste the full contents of your `service-account.json` into `MMM_SYNC_GOOGLE_SERVICE_ACCOUNT_JSON`
6. Upload or recreate your `world-map.json` on the server, or bake it into the repo if you are comfortable doing that

### Public URL

Once deployed, use:

```text
https://your-service-host/mmm/update
```

and in AeTweaks set:

- `Mmm Sync = true`
- `mmmSyncUrl = https://your-service-host/mmm/update`

## Mod config

In AeTweaks:

- enable `Mmm Sync`
- set `mmmSyncUrl` to your backend URL, for example:
  - `http://127.0.0.1:8787/mmm/update`
- set `mmmSyncApiKey` if you configured one on the backend

## Notes

- the backend uses `batchId` to ignore retried uploads safely
- if a world/server is not mapped and `MMM_SYNC_REQUIRE_WORLD_MAPPING=true`, the update is rejected instead of writing a wrong column
- by default the backend now falls back to the `default` mapped column when a specific world/server name is not found
