# MMM Sync API

AeTweaks can safely sync Manual Mining Leaderboards progress by sending dig deltas to your own backend. The mod does not write to Google Sheets directly.

## Client Config

Set these in the AeTweaks generic config:

- `mmmSyncUrl`
- `mmmSyncApiKey` (optional)
- enable the `Mmm Sync` toggle

## Request

Method:

```http
POST /mmm/update
Content-Type: application/json
X-AeTweaks-Key: optional-api-key
```

Example payload:

```json
{
  "batchId": "8f4fbe12-c58f-43b6-a8ab-f253a4b1a102",
  "username": "Aitorthek1ng",
  "uuid": "player-uuid",
  "worldId": "my_server_id",
  "worldName": "My Server",
  "serverAddress": "play.myserver.net:25565",
  "multiplayer": true,
  "deltaBlocks": 25,
  "sessionBlocks": 4215,
  "sentAt": 1770000000000,
  "modId": "miningtrackeraddon",
  "modName": "AeTweaks",
  "modVersion": "1.0.5",
  "minecraftVersion": "1.21.4"
}
```

## Expected backend behavior

1. Authenticate the request if you use an API key.
2. Ignore duplicate `batchId` values so retries never double-count.
3. Verify the player exists in the MMM sheet.
4. Locate the correct player row using `uuid` if you maintain a UUID map, otherwise `username`.
5. Resolve the correct world/server digs target using:
   - exact world name
   - server address / host
   - configured aliases and fallback mapping
6. Increment:
   - the player `Total Digs`
   - the correct world/server column for `worldName`
7. Return success only after the write is committed.

## Suggested response

```json
{
  "ok": true
}
```

For failures, return a non-2xx status. The client will keep the batch queued and retry later.
