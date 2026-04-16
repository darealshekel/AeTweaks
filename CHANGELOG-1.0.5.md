# AeTweaks 1.0.5

Release date: 2026-04-16

Changes since 1.0.4:

## Sync and Website Linking

- Added the new website account linking flow inside the mod.
- Added website link code support and local account-link persistence.
- Added queued cloud sync delivery and retry handling for unreliable or offline periods.
- Added pending sync storage and queue management for deferred uploads.
- Added source scanning and source resolution for uploaded sessions.
- Added scoreboard parsing, personal total detection, and player digs parsing to improve mined-block sync accuracy.
- Added Carpet fake-player detection to reduce bad sync data.
- Added Aeternum leaderboard reading and snapshot support.
- Fixed authoritative total drift and source leaderboard aggregation behavior.
- Added server favicon capture to source scan payloads so server identity can be sent with sync evidence when available.

## UI and UX

- Reworked the Website Link screen to match the website's dark red, charcoal, and warm gold visual system.
- Applied the same website-aligned visual style to Summary, Session History, and Projects screens.
- Restyled the tweak mining HUD bounding box to use the website visual language.
- Improved the Project Manager screen styling and layout.
- Added hover detection on the Session Pace chart to inspect blocks mined per hour at each point.

## Session and Storage Improvements

- Updated session storage and world session context handling to support the new sync flow.
- Improved session presentation and pace visualization inside Session History.
- Updated config, feature toggle, hotkey, and GUI wiring for the new sync and website-link features.

## Compatibility

- Updated Fabric Loader compatibility from 0.16.7 to 0.16.9.
