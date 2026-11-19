# Replicate this launcher for your own group

This repo is a fork of CleanYourTV, turned into a home-screen launcher that shows a row of posters recommended by a Facebook group, refreshed every morning. It was built for The Reviewer Collective (a private Facebook group, about 27,000 members). This file tells an AI agent (or a technical human) how to build the same thing for a different group, a different feed, or a different country.

Point your agent at this file. It is the whole recipe.

## Two paths

**Path A — just install it.** If you only want this launcher on your TV, showing The Reviewer Collective's recommendations, you do not need to build anything. Follow the step-by-step install guide written for zero technical knowledge: https://docs.google.com/document/d/1aFq2Bzubmspq3bCh1oJYCG0TD9wekO6GTWKUyoEqMog/edit?usp=drivesdk — about 15 minutes, free, Android TV / Google TV only (not Samsung, LG, Roku, or Fire TV). Download the APK from the guide and send it to the TV with the free "Send Files to TV" app.

**Path B — build your own.** If you want the same launcher but fed by YOUR group, YOUR country, or YOUR list, read on. You will fork this repo, point it at your own feed, and run a small daily pipeline that turns your group's posts into that feed. That is what the rest of this file covers.

## How it fits together (3 pieces)

1. **The launcher (Android TV app, this repo).** A fork of CleanYourTV. On resume and once an hour it fetches two JSON files from GitHub (raw URLs). It renders the feed as a "For you" poster row and the cinema file as an "In cinemas" row. No account, no server, no rebuild needed to update the recommendations. Only the JSON changes.
2. **The feed (two JSON files, also in this repo).** `recommendations.json` is the poster row. `cinema.json` is the cinema row. Both are plain files on the `main` branch; the TV reads them via `raw.githubusercontent.com`.
3. **The pipeline (a script plus a daily job, NOT in this repo).** Reads the Facebook group's new posts, resolves each title against TMDB, checks streaming availability and the deeplink, and writes the two JSON files. It runs once a morning. One step needs judgement (is this post actually recommending something?) — that step is done by a human or an agent reading the posts, not by the script.

The TV never talks to Facebook or TMDB directly for the feed. It only reads the two JSON files. That is what makes the daily update cheap and reliable.

## Part 1 — The feed contract

### `recommendations.json`

Top level: `count`, `generated_at` (ISO 8601 with timezone), `source` (a short name for your feed, e.g. `"reviewer-collective"`), `source_group_id` (the Facebook group id, if any), `titles` (array, newest first).

Each title:

| Field | Type | Notes |
|---|---|---|
| `title` | string | Display title. Use TMDB's title when it differs from the group's wording. |
| `tmdb_id` | number | TMDB id. Never guess it; resolve it by TMDB search and confirm the year and type match. |
| `tmdb_type` | string | `"movie"` or `"tv"`. |
| `media_type` | string | Same as `tmdb_type`. |
| `year` | number | Release/first-air year. |
| `language` | string | ISO 639-1 code (`"en"`, `"hi"`, `"ml"`, `"ko"` …). The launcher shows the full language name on the card. |
| `genres` | string[] | Max 2 is ideal; the card shows them after the language. |
| `overview` | string | TMDB synopsis. Required — the details dialog shows it. |
| `poster_url` | string | `https://image.tmdb.org/t/p/w342/<poster_path>` |
| `poster_path` | string | TMDB poster path, e.g. `"/abc123.jpg"`. |
| `backdrop_url` | string | `https://image.tmdb.org/t/p/w780/<backdrop_path>` (w780). The launcher uses this for the background glow. |
| `backdrop_path` | string | TMDB backdrop path. |
| `availability` | array | One entry per country/provider where it streams. Each entry: `country` (`"SG"`, `"IN"` …), `provider` (display name, e.g. `"Netflix"`), `provider_url` (the deeplink, see below), `type` (`"sub"`, `"rent"`, `"buy"`, `"free"`), `url_source` (how the URL was verified, e.g. `"netflix_live_page_verified"`), `requires_vpn` (true/false/null), `vpn_label` (e.g. `"ExpressVPN to India"` or null). |
| `post_url` | string | Link back to the Facebook post that recommended it. |
| `first_seen` | string | Date (`YYYY-MM-DD`) the title first entered the feed. |
| `last_seen` | string | Date it was last re-confirmed in the group. |
| `imdb_id` | string or null | e.g. `"tt38265603"`. |

Example (abridged from the live feed):

```json
{
  "title": "Murder in Galicia",
  "tmdb_id": 303182,
  "tmdb_type": "tv",
  "media_type": "tv",
  "year": 2026,
  "language": "es",
  "genres": ["Crime", "Mystery", "Drama"],
  "overview": "In Pazos, Irene is brutally attacked, and Civil Guard sergeant Elias suspects her husband Aniceto...",
  "poster_url": "https://image.tmdb.org/t/p/w342/pk7ewQL2vAxtit0YmDEURmAInS9.jpg",
  "backdrop_url": "https://image.tmdb.org/t/p/w780/jM5dhDrpRiVxuzcvAEeHhcAKt50.jpg",
  "availability": [
    {"country": "SG", "provider": "Netflix", "provider_url": "https://www.netflix.com/title/82023389?source=30", "type": "sub", "url_source": "netflix_live_page_verified", "requires_vpn": null, "vpn_label": null},
    {"country": "IN", "provider": "Netflix", "provider_url": "https://www.netflix.com/title/82023389?source=30", "type": "sub", "url_source": "netflix_live_page_verified", "requires_vpn": null, "vpn_label": null}
  ],
  "post_url": "https://facebook.com/groups/1744693935673270/permalink/4065870946888879/",
  "first_seen": "2026-11-19",
  "last_seen": "2026-11-19",
  "imdb_id": null
}
```

Rules the pipeline enforces (all deliberate, keep them unless you have a reason not to):

- Newest first. Dedupe on TMDB id + type. Likes and comments never affect order.
- At least 7 cards at all times. Never publish a feed with fewer than 7. Never lower the gates to hit 7.
- NO rating gate. A positive recommendation from the group IS the quality gate. Do not add a TMDB/IMDb score threshold on top of it.
- Negative-review posts quarantine (they do not publish). Quarantine everything that fails a check, with a reason recorded. Never silently drop a title.
- One clear title per post. A post with no identifiable title, or an opinion/casting-news post with no recommendation, quarantines.
- Every title needs a real streaming source in SG and/or IN (or your countries) and a verified deeplink. See the provider rules below.
- Check the user's skip list before publishing (titles the user already watched or removed from the TV must not reappear).
- Schema-validate before publishing. Keep a last-good copy. Never publish a broken feed. On any failure, leave the last-good feed in place and report it.

### `cinema.json`

Top level: `titles` (array, at most 2), `verified_on` (date), `verified_via` (e.g. `"Shaw / Golden Village Singapore listings"`), `source`, `notes`.

Each title: `title`, `tmdb_id`, `year`, `language` (full name here, e.g. `"Hindi"`, `"English"`), `genres` (array), `overview`, `poster_path`, `rating` (TMDB vote average), `runtime_minutes`.

Rules: only titles verified as currently screening in your city/country, re-confirmed each run, aged out after 7 days without re-verification. A cinema-only title with no verified screening stays in quarantine, it does not go in the main feed either. The launcher hides the whole "In cinemas" section when `verified_on` is more than 7 days old.

### Provider URL rules (verified on a real TV)

These are the deeplink forms that actually open the title on the TV. Do not invent URLs; each one must be found and verified against the live service page.

- **Netflix:** `https://www.netflix.com/title/<id>?source=30`. The Netflix id must be found (web search / TMDB homepage / Netflix's own page) and verified against the live Netflix page. Never guess an id.
- **Apple TV:** `tv.apple.com/...` in its verified form.
- **YouTube:** `watch?v=...`.
- **Prime Video:** ONLY `app.primevideo.com/detail?gti=...`, and only from the title's own in-app Share link on the account that will watch it. Prime title deeplinks are currently BROKEN on this TV (they fail to open), so Prime titles quarantine until a working in-app Share GTI is supplied. Do not publish a Prime URL you have not seen open on the TV.
- **SonyLIV / ZEE5:** deeplinks unverified on this TV — quarantine until signed in on the TV and re-tested.
- **JioHotstar:** geo-blocked in Singapore — quarantine.
- **BBC Player:** UK-only — quarantine.

If a title is only on a provider whose deeplink you cannot verify, quarantine it. A card that does not open is worse than a missing card.

## Part 2 — The daily pipeline (how the feed gets made)

The pipeline is a script plus a daily scheduled job. It is not in this repo because it is specific to one group's access method and one person's credentials. Rebuild it for your own setup following these steps. The logic below is exactly what the live pipeline does.

### Step 1 — Pull new posts

Read the group's new posts since the last run. Keep a watermark (the `creation_time` of the last post you successfully published) and only pull posts newer than it. Save the raw posts for the run.

**This is the hard part, be honest about it.** Facebook removed the Groups API in April 2024. There is no supported API to read a private group. The live pipeline reads the group through the Muse agent's `facebook_cli` connector, which reads the group as a logged-in member. Your agent will need its own way to read your group's posts — whatever read access its own tools provide as a member of the group. If your agent has no way to read the group, the pipeline cannot run automatically; the fallback is a human pasting the day's new posts into the pipeline by hand. Do not fake a feed. If the group read breaks (login expired, permission revoked), stop, leave the last-good feed in place, and tell the person to re-approve access.

### Step 2 — Adjudicate (the judgement step)

A human or an agent reads the pulled posts and writes a `candidates.json` for the run. For each post, decide:

- `action`: `"publish"`, `"cinema"` (verified screening, see below), or `"quarantine"`.
- `title`: the title as named in the post.
- `tmdb_id`: only when the group's title differs from TMDB's (e.g. the group says "Ramayana Part 1", TMDB lists it as "Ramayana: Rise of a Legend", TMDB 656908). Otherwise leave it for the script to resolve by search.
- `reason`: for quarantine, why (no clear title, negative review, no verified streaming source, Prime deeplink unverified, Hotstar geo-blocked, etc.).
- For `cinema`: `verified_via` (which cinema listing you checked) — the script re-confirms the current cinema titles each run.

Positive signal only. A post that recommends a title publishes. A post that pans it, or is just news/casting chatter with no recommendation, quarantines.

### Step 3 — Build

The script takes `candidates.json` and, for each `publish` candidate: resolves the TMDB id (search, confirm year and type), pulls details (title, year, language, genres, overview, poster/backdrop paths, IMDb id), checks watch providers for your countries, verifies the provider URL, and builds the title entry. It merges with the existing feed (newest first, dedupe TMDB id + type), checks the skip list, enforces the 7-card minimum, and schema-validates the result. Cinema candidates become `cinema.json` (max 2, `verified_on` = today). Everything that fails a check goes to `quarantine.json` with its reason.

### Step 4 — Push and verify

Push `recommendations.json` (and `cinema.json` when it changed) to the GitHub repo's `main` branch. Then fetch the live raw URLs and schema-validate what the server actually returns. If the push or the verification fails, do not publish — leave the last-good feed in place.

### Step 5 — Schedule it

Run it once a morning (the live one runs about 06:51 Singapore time). The order is: pull, adjudicate, build, push, verify. On failure, leave last-good in place and report it the same day.

### State the pipeline keeps

- `watermark.json` — last published post `creation_time`.
- `skipped.json` — titles the user skipped on the TV (TMDB id + type). Checked before every publish.
- `last_good/` — the last successfully published feed and quarantine, so a failed run never leaves a broken feed live.
- `runs/<date>/` — per-run raw posts, `candidates.json`, built feed, quarantine, and a build summary, so any run can be audited.

## Part 3 — Build the launcher

### What you need

- A computer that can run Android Studio or the Android command-line tools (a Mac or Linux machine; a phone cannot compile an Android app).
- Android SDK + JDK 17.
- A free TMDB API key (themoviedb.org, free account). It powers on-TV search.
- A GitHub account and a fork of this repo (the feed lives on your fork).
- A TV or emulator to test on, plus ADB access to the TV for installing and screenshots.

### The one-line change (point it at your feed)

In `launcher/app/src/main/java/com/gathin/tvhome/BentoFeedFetcher.java`, line 29:

```java
static final String FEED_URL = "https://raw.githubusercontent.com/<your-user>/<your-fork>/main/recommendations.json";
```

In `launcher/app/src/main/java/com/gathin/tvhome/BentoCinemaFetcher.java`, line 32, the same for `cinema.json`:

```java
static final String CINEMA_URL = "https://raw.githubusercontent.com/<your-user>/<your-fork>/main/cinema.json";
```

That is the only code change needed to feed the launcher from your own list. Everything else (layout, dock, search, My List) works as-is.

### Build

1. Clone your fork.
2. Put your TMDB key in `local.properties` at the repo root (it is git-ignored, never commit it):
   ```
   tmdb_api_key=YOUR_KEY_HERE
   ```
   The build reads it into `BuildConfig`. The same key is used by the pipeline.
3. Bump the version in `launcher/app/build.gradle` (`versionCode` +1, `versionName` to your new version). Note: `versionName` uses single quotes.
4. From `launcher/`, run:
   ```
   ./gradlew :app:assembleRelease
   ```
   The APK lands at `launcher/app/build/outputs/apk/release/app-release.apk` (about 628 KB).
5. Install on the TV with ADB (`adb install -r app-release.apk`), or by sending the APK to the TV with the "Send Files to TV" app (the method in the install guide). Press Home and choose the launcher, tap Always.

### What is already in the launcher (do not rebuild these)

- **For you row:** the live feed, newest first, poster cards with a fading bottom scrim, title, language first in the genre line, provider icon in the corner (Netflix uses a small red N drawable because its launcher icon is the full wordmark; other providers use their installed app icon, text fallback when unknown).
- **In cinemas row:** from `cinema.json`, hidden when `verified_on` is more than 7 days old.
- **My List:** long-press a poster to save it (local only, cap 100, dedupe TMDB id + type). A saved title hides from the For you row until removed.
- **Skip:** long-press, Remove, in the For you row — the title goes to a local skip list and the daily refresh must not re-show it. A "Removed (n)" card restores skipped titles.
- **Search:** a tile at the start of My List. Searches TMDB (SG + IN) and shows where each result streams. Needs the TMDB key.
- **Dock:** 6 tiles at the top (YouTube, Netflix, Prime Video, Apple TV, ExpressVPN, JioHotstar). Change them in the layout if your apps differ.
- **Weather:** NEA Singapore weather on the home screen. Replace `BentoWeather` with your country's source if you are not in Singapore.
- **Greeting:** shows the household names. Change them in the layout.
- **Feed fetch:** on resume and hourly, with a throttle. A failed fetch clears the attempt so the next resume retries (a failed fetch must never block retries for an hour). The bundled `assets/recommendations.json` and `assets/cinema.json` are only the offline fallback.
- **Deeplinks:** Netflix works. Prime title deeplinks do not (see provider rules). Hotstar is geo-blocked in Singapore.

## What you need from the person (checklist)

Ask for these up front, in one message, before building:

1. Which Facebook group (name + id, and confirmation the agent can read it as a member)? If the group is private and the agent cannot read it, say so now — the daily refresh cannot be automated.
2. Which countries' streaming catalogues (e.g. SG + IN)? This decides the watch-provider checks and the `availability` entries.
3. A free TMDB API key (or permission to create one on their TMDB account).
4. A GitHub account to fork the repo into (the feed will live there).
5. A computer that can build Android apps, and ADB access to the TV (or willingness to install via "Send Files to TV").
6. The household names for the greeting, and which 6 apps belong in the dock.
7. Their country's weather source, if not Singapore (or skip weather).

## Honest limitations

- **The Facebook group read is the fragile part.** It depends on the agent's own access to the group. If that access breaks, the feed stops updating until access is restored. The launcher keeps showing the last-good feed, so the TV never goes blank.
- **The TMDB key is baked into the APK.** It only powers on-TV search, it is a free key, and TMDB limits are per-IP. If you share your APK publicly, anyone can extract the key. If that bothers you, build a keyless version (search disabled) for sharing.
- **Prime deeplinks do not work on this TV.** Prime titles will quarantine until a working in-app Share link is supplied.
- **This is a fork of CleanYourTV (GPL).** Keep the licence and the upstream attribution if you redistribute it.
- **No Play Store.** The launcher is sideloaded, so the TV will ask for install permission once. That is normal for an app installed this way.

## Links

- This repo: https://github.com/sakshigopal7/cleanyourtv
- Live feed: https://raw.githubusercontent.com/sakshigopal7/cleanyourtv/main/recommendations.json
- Live cinema list: https://raw.githubusercontent.com/sakshigopal7/cleanyourtv/main/cinema.json
- Install guide (no technical knowledge needed): https://docs.google.com/document/d/1aFq2Bzubmspq3bCh1oJYCG0TD9wekO6GTWKUyoEqMog/edit?usp=drivesdk
- Upstream CleanYourTV: https://github.com/maxhayim/cleanyourtv
- The Facebook post this came from: https://facebook.com/groups/1744693935673270/permalink/4065568163585824/
