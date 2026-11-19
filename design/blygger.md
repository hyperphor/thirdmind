# General idea
Use this instead of goddinpotty to publish blyg 
– for fast turnaround 
– general deprecation of goddinpotty

# Marketing
Bleah. But you could package it as an add-on to any Markdown notetaking system


# Use case
- Write something in AMMDI, tagged #blyg, it gets published.
- Maybe with a #published gate
- Have to experiment there. 

# Issues
- has to keep blygger state, presumably copying what goddinpotty does now
- should import old blygger stuff for reference, not republish (well, republish in the sense of regenerating the web pages or whatever)
- links to new or updated AMMDI pages won't work quite right until we get general publishing working



# Research

## Competitors/inspirations

### Obsidian plugin
https://github.com/florianlohse/blyg-obsidian/tree/main

Requires Blygger Studio

Seems to do some interesting quote/respond/stub but I just don't understand any of that.

# Plan

## What exists now (goddinpotty)

- `goddinpotty.export.blygger` (~1.6k lines) + `blyg_remote` + `syndicate`: protocol 0.3 publish
  side. Fragments; threads (a tagged block with children → thread, children promoted to their own
  fragments); `#blyg-pin`; `#blyg-reply` stubs with remote snapshots and webmentions; Mastodon /
  Bluesky syndication; "From [Page]" attribution; titles; archive + permalink pages; feed.xml,
  blyg.json, items/index.json; withdrawal is opt-in and leaves tombstones. Design notes in
  `goddinpotty/design/blygger.md`, explainer in `blygger-explained.md`; spec in
  `/opt/reposed/blygger-spec/docs/` (0.4 is now the living draft; goddinpotty is on 0.3).
- State: `/opt/mt/repos/ammdi/.blyg-state.edn` (tracked in ammdi, committed by Logseq's
  auto-save). `{:items {block-uuid entry} :remotes {...} :syndication {...}}`. 415 items, ~111
  top-level, the rest promoted thread children. It is the one irreplaceable artifact: blyg-ids,
  versions and changelogs can't be rebuilt from the published surface.
- Served at `https://ammdi.hyperphor.com/blyg/`, written into `hyperphor-git/ammdi/blyg/` by the
  goddinpotty build, deployed by `bin/publish-ammdi.sh` (daily, launchd; commit + push scoped to
  `ammdi/`).

## Constraints that shape the plan

1. **Old identities don't carry over directly.** State is keyed by Logseq's in-db `:block/uuid`,
   and none of the 415 keys appear among the 304 `id::` uuids in the markdown (Logseq only writes
   ids for referenced blocks). ThirdMind sees `page~path` ids, which change when blocks move.
   Logseq's transit cache (`~/.logseq/graphs/logseq_local_++opt++mt++repos++ammdi.transit`, what
   goddinpotty reads) does hold many of them (21 of a 40-key sample), so part of the mapping can
   be exact. That's a one-time use at migration; afterwards blygger keys on ThirdMind's own ids.
2. **Items must stay 200 forever** and the origin URL is the blyg's identity, so ThirdMind must
   publish at the same `/blyg/` origin and carry every existing item (and blyg-id) forward.
3. **goddinpotty wipes `output-dir` on a full build**, which would delete a `/blyg/` written by
   anyone else.
4. **`content_md` is a wire format**: its hash drives versions, and other blygs quote specific
   versions (a remote snapshot cites `data-blyg-version="2"`). ThirdMind's markdown won't be
   byte-identical to goddinpotty's: its link URLs (`/William-Burroughs`) and attribution anchors
   (`/Blygger#<logseq-uuid>`) come from goddinpotty's naming and Logseq uuids.
5. **The live state uses more than fragments.** 3 items have pinned versions (their
   `items/{id}/v{n}.json` must stay 200), 16 carry transclusions, there are remote snapshots, and
   20 items have been syndicated. No reply stubs yet.
6. **goddinpotty is still publishing daily** (launchd `publish-ammdi.sh`), and the state file
   changes with it. Anything that writes to the graph or to `/blyg/` before goddinpotty's
   blygger is switched off risks duplicates (a new uuid → a new item → syndicated to
   Mastodon/Bluesky).

## Decisions to make (recommendations first)

- **D1 identity — decided: ThirdMind owns block ids** (see Block identity below). blygger keys
  its state on ThirdMind block ids; Logseq uuids are not used going forward.
- **D2 migration.** Import `.blyg-state.edn` into ThirdMind's own state file at cut-over (it
  changes daily until then; earlier imports are dry runs only). Map entries to current blocks
  (transit cache read once, then normalized content; or content only), rekey each to its
  ThirdMind id, and keep the old Logseq uuid only as a legacy field, since frozen content_md
  contains `/Blygger#<logseq-uuid>` anchors. Imported entries
  keep blyg-id, version, changelog, pins *and their content_md/hash, frozen*: ThirdMind records
  its own source hash as a separate baseline and bumps the version only when that changes
  (an actual edit). Rendering-only differences go into content_html without a bump, as
  goddinpotty's `reseal!` does. Unmatched entries stay frozen (served, never re-derived). Check
  the 0.3/0.4 versioning section on whether a version's content_hash may ever change.
- **D3 output.** ThirdMind writes `/blyg/` directly into `hyperphor-git/ammdi/blyg/`; goddinpotty
  gets `:blygger :enabled? false` plus a small change to carry `blyg/` across full builds.
  (Alternative: ThirdMind writes elsewhere and goddinpotty copies it in — keeps one writer, but
  then publishing waits for a goddinpotty build, defeating "fast turnaround".)
- **D4 deploy.** Start with a manual `lein blyg publish` + an explicit push command; automate the
  push from the watcher only once trusted (it's outward-facing).
- **D5 the #published gate** (from Use case): defer; `#blyg` stays the only gate at first.

## Block identity (ThirdMind-wide, not just blygger)

Per `metadata.md`: no uuids in the .md, so ids live in ThirdMind's store and are matched to text
blocks when a page changes.

- **Mint**: ThirdMind assigns an opaque id (format its own choice; could simply be blygger's
  base32 form) the first time it sees a block. `ingest/block-id`'s `page~path` becomes a
  *location*, not the identity; an existing `id::` stays a ref target/alias (for `((ref))`),
  not the identity.
- **Persist across rebuilds**: today `lein run` builds a fresh db, which would lose every id.
  Identity needs its own durable table (id → page, path, content signature, last seen), carried
  through rebuilds and full re-ingests, and backed up (it is now as irreplaceable as the blyg
  state).
- **Match on change**: when `update-file!` re-ingests a page, align old blocks to new ones:
  same content+path → same id; same content elsewhere on the page → moved; same path and
  parent, similar content → edited; otherwise new id. Then cross-page: a block that vanished
  from one page and appeared on another with the same content → moved between pages. Ambiguous
  cases get new ids and are logged. goddinpotty's `rekey-moved-blocks` signatures are a starting
  point.
- **Edits from ThirdMind's own UI are exact**: the editor knows which block it changed, so
  block-op saves carry ids across without matching. Only Logseq-side edits need matching.
- **Gaps**: changes made while ThirdMind isn't watching (git pull, Logseq on another machine)
  arrive as one big diff; matching still applies, just with more ambiguity. Git history (as in
  `times`) can disambiguate later if needed.
- **Replay result (go).** `identity.clj` matches by content alone; `lein replay-ids` replays
  ammdi's git history and scores it against `id::` uuids, which the matcher never sees
  (non-trivial blocks only). Consecutive versions (9073 pairs): unchanged 100%, moved 99.99%,
  edited 98.2% kept, with 6 of 2735 edits getting a wrong id, all merges or splits. Blocks
  rewritten beyond recognition (similarity < 0.5) mostly get a new id, as intended. Ten
  versions apart (ThirdMind not watching): about the same. Not yet measured: cross-page moves.
- **Wired in (decided: a separate attribute).** Block objects carry `:tmid`; the URI stays
  `page~path`. Ids live in `<db>.ids.db` (`ids.clj`), which `fresh-store!` leaves alone: one
  record per graph *file* (so `title::` edits and page-name collisions don't disturb them),
  matched against on every ingest, so a rebuild and an unwatched gap are the same case. A
  vanished file's record becomes an orphan; a file with no record (a rename) takes back ids
  from orphan blocks with unique, non-trivial content. On ammdi: stable across rebuilds,
  about 5 s added to a full ingest. Not yet: cross-page moves of single blocks, ids for UI
  edits (exact carry-over), backup of the ids db.
- Accepting some drift: an id lost to a mis-match means a published item looks withdrawn and a
  new one appears. For blyg that's survivable (withdrawal is opt-in, so the old item just stops
  updating) but worth reporting in `lein blyg --dry-run`.

## Phases

**0. Read-only groundwork.**
- Block identity: the durable id table, matching in `update-file!`, and a test harness that
  replays ammdi's git history (consecutive commits of each file) to measure how often ids
  survive real edits. Go/no-go for the whole approach.
- `blyg/content.clj`: a block (+ children) → `content_md` / `content_html`, using ThirdMind's own
  render.clj, stripping the tag, absolutizing links to `https://ammdi.hyperphor.com/<page>` with
  goddinpotty's exact URL scheme (page-name slugs, not ThirdMind's percent-encoded keys).
  Which pages count as published (so links aren't dead or private): read the set of files
  actually present in `hyperphor-git/ammdi/`, until ThirdMind does general publishing.
- **Done: `blyg/content.clj`.** Fragment and thread `content_md`, fragment `content_html`
  (ThirdMind's renderer, with `:page-href` hooks so links go to the site, or are plain text
  for unpublished pages). On ammdi, 197 of 342 live fragments come out byte-identical and
  245 within 90% similarity (by content pairing; the migration dry run will pair properly).
  Deliberate differences from goddinpotty: emphasis kept (it drops `*x*`/`**x**` markers),
  images made absolute and Logseq size attrs dropped (it leaves `../assets/` paths that break
  in other readers). Exit tags come from the caller; nothing personal is hard-coded. The
  attribution anchor is the block's `id::` uuid if it has one, else no anchor. Not yet:
  titles, thread HTML (needs child entries: phase 1), reply stubs.
- Privacy as goddinpotty: explicit exit tags (`#Private`, `#ExitPoint`) on the block, its
  ancestors or page, and private children dropped; `#blyg` overrides mere unreachability.
- Port the wire pieces nearly verbatim: base32 ids, sha256 content hash, item/manifest/index
  JSON, feed.xml, tombstones.
  **Done: `blyg/wire.clj`.** Same entry shape as goddinpotty's state, so imported entries pass
  through as is. `lein blyg parity` regenerates the surface from `.blyg-state.edn` and diffs it
  against the live `/blyg/`: all 431 files match (JSON parsed-equal, feed.xml byte-equal).
  Not ported: the HTML archive/permalink pages (site look, phase 1), syndication, remotes.
- Migration dry run: map the 415 entries to current blocks (transit cache, then content) and
  report exact / content-matched / ambiguous / unmatched, plus how many would differ in
  content_md if re-derived. Go/no-go for D2.

**1. Publisher, parity with goddinpotty for everything the live state uses:** fragments, threads,
transclusions, pins (incl. `#blyg-pin`-only blocks as candidates), remote snapshots carried
through, tombstones.
- `blyg/state.clj`: ThirdMind state file (EDN, in the ammdi repo next to the old one), written
  after surfaces, as goddinpotty does.
- `blyg/publish.clj`: candidates from the db (`#blyg` blocks via refs), next-entry / version bump
  on hash change, withdrawal opt-in, surfaces (manifest, index, items, feed, archive and
  permalink pages; site look borrowed from the ThirdMind CSS for now).
- `lein blyg [--dry-run] [--withdraw]` writes into a staging dir. Diff against the live
  `hyperphor-git/ammdi/blyg/`: item ids, versions and kinds should match exactly; content
  differences reviewed by hand.

**2. Fast turnaround.**
- Watcher hook (the existing `"blyg"` handler slot, but on any change to a `#blyg` block or its
  descendants, not just a newly added tag): re-derive just the affected items, then rewrite
  index/feed/manifest. Debounced, since Logseq saves keystroke by keystroke.
- Publishing in the edit UI: show a block's blyg status (published, version, link) in the
  margin. Optionally a "publish now" action.

**3. Cut-over** (one sitting, in this order).
- Turn off goddinpotty's blygger (`:enabled? false`) and teach its build to keep `blyg/`; let one
  daily run go through to confirm `/blyg/` survives untouched.
- Final state import (D2), then ThirdMind writes the live dir; diff against what was there.
- `lein blyg push`: commit + push `hyperphor-git/ammdi/blyg` only (never `git add -A` there).
- Conformance check against the spec's checker before and after.

**4. The rest, roughly in order.** Syndication (port `syndicate`, secrets stay out of the repo;
it has to be here before goddinpotty's is retired for good, or new items stop being posted);
`#blyg-reply` stubs and webmentions (port `blyg_remote`);
`<link rel="blyg">` on published pages; protocol 0.4 (manifest-located surface, `[[id | text]]`
labels, `links[]`).

## Branches

`blygger` is off `times`, which doesn't have the editor. Phases 0–1 write nothing to the graph,
so they don't need `edit`. The edit-UI parts of phase 2 (and exact id carry-over on UI edits) do;
merge `ui`, `times` and `edit` into main before phase 2.

## Open questions

- Should ThirdMind's state live in the ammdi repo (auto-committed by Logseq, like now) or with
  ThirdMind?
- Blocks whose children are AskClaude answers: goddinpotty renders them as hover popups; here
  they'd be the collapsed quote style from the UI. Acceptable on the wire?
- Long-term, is this the first piece of ThirdMind's general publishing (Level 1 in ui.md)? If
  so, the "published pages" set in phase 0 should become ThirdMind's own, not read from
  goddinpotty's output.
