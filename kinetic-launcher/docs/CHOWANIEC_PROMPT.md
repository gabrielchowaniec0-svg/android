# Prompt to paste into your Chowaniec chat

Copy everything below the line into the chat where you built Chowaniec. Once that
work is merged, tell me in the Kinetic chat and I'll add the Kinetic side
("post in group chat …", "note …", "start a watch party", and the Today-page counts).

---

I'm building **Kinetic**, a custom Android launcher for my Galaxy S25 Ultra. I want
Kinetic to control Chowaniec (chowaniec.net) directly. For example, typing
"post in group chat dinner's ready" in Kinetic's search box should post it. Please
add a small, secure **Kinetic API** to Chowaniec.

**1. Personal access token**
- Add a page in Settings: **Settings ▸ Connected apps ▸ Kinetic**.
- On that page I can create, view once, copy and revoke a long random token tied to my account.
- Store only a hash of the token on the server.
- Every Kinetic API request sends it as `Authorization: Bearer <token>`.
- Revoking the token must take effect immediately.
- Rate-limit each token to about 60 requests a minute.

**2. Endpoints** (JSON in, JSON out, all under `/api/kinetic/`)
- `POST /api/kinetic/group-message` with `{ "text": "…" }` posts to the family group chat as me and returns `{ ok, id }`.
- `POST /api/kinetic/message` with `{ "to": "<name or user id>", "text": "…" }` sends a direct message. Match the name the same forgiving way the app's own search does, and return `{ ok, to: { id, name } }`. If the name is ambiguous, return `{ ok: false, choices: [...] }`.
- `POST /api/kinetic/note` with `{ "text": "…", "title": "optional" }` creates a note and returns `{ ok, id, url }`.
- `POST /api/kinetic/list-item` with `{ "list": "Shopping", "text": "milk" }` adds an item to a list, creating the list if it doesn't exist.
- `POST /api/kinetic/watch-party` with `{ "url": "optional video link" }` starts a watch party and returns `{ ok, url }` so Kinetic can open it.
- `POST /api/kinetic/event` with `{ "title": "…", "start": "ISO-8601", "end": "optional" }` adds a calendar event.
- `GET /api/kinetic/summary` returns:
  - `{ unreadMessages, unreadGroup, nextEvent: { title, start } | null, latestNote: { title, url } | null }`
  - Kinetic will show this on a "Today" page and in the Dynamic Island, so keep it fast (cached for about 30 seconds).
- `GET /api/kinetic/ping` returns `{ ok, user: { name } }` so Kinetic can check that the token works.

**3. Rules**
- Validate everything: trim text, reject empty text, and cap the length (messages 2,000 characters, notes 20,000).
- Return clear error messages Kinetic can show, like `{ ok: false, error: "Token revoked" }`.
- Never expose other people's data in these endpoints beyond the names needed for `message`.
- Log each call (time, endpoint, success) in a small "Recent activity" list on the Connected apps page, so I can see what Kinetic did.

**4. Notifications that Kinetic can show nicely**
- Kinetic's Dynamic Island shows incoming Chowaniec messages and can reply to them inline. Please make the push notifications:
  - use the sender's name as the title
  - use the message text as the body
  - use the sender's avatar as the icon
  - group messages by conversation
- If Chrome on Android supports it for web push, add a **Reply** action that posts back to the right conversation. If it doesn't, add a **Mark as read** action instead.

**5. Deep links**
- Make sure these open the right screen when launched from outside the app:
  - `/messages/?to=<user>` (opens a chat with that person)
  - `/notes/new?text=<prefilled>`
  - `/watch/new`
- Add a **Web Share Target** to the manifest, so sharing a link or text from any Android app can go to "Chowaniec ▸ Send to group chat" or "Save as note".

Please include short instructions for where to find and copy the token, and an example `curl` request for each endpoint, so I can test them before connecting Kinetic.
