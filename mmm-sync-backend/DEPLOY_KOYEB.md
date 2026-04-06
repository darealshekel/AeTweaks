# Deploy AeTweaks MMM Sync on Koyeb

This backend is a good fit for a free Koyeb web service because it is a small Java HTTP service with one public endpoint.

## What you need

- the code pushed to GitHub
- your Google Sheet shared with the service-account email as `Editor`
- the full JSON contents of `service-account.json`

## Koyeb steps

1. Sign in to [Koyeb](https://www.koyeb.com/)
2. Create a new `Web Service`
3. Choose your GitHub repo
4. Point the service to the `mmm-sync-backend` directory
5. Use the included `Dockerfile`
6. Add environment variables:
   - `MMM_SYNC_SHEET_ID=1ZYHywH13pjoUGUx-BZU_kncINbcxAnRGNW2MAZliiyA`
   - `MMM_SYNC_SHEET_GID=450768443`
   - `MMM_SYNC_PLAYER_COLUMN=I`
   - `MMM_SYNC_TOTAL_COLUMN=J`
   - `MMM_SYNC_REQUIRE_WORLD_MAPPING=false`
   - `MMM_SYNC_GOOGLE_SERVICE_ACCOUNT_JSON=<paste full JSON here>`
7. Deploy

## Health check

Use:

```text
/health
```

You should get:

```json
{"ok":true}
```

## AeTweaks config

After deploy, copy the public URL and set:

```text
mmmSyncUrl=https://your-koyeb-url/mmm/update
```

Then enable `Mmm Sync` in the mod and start mining.
