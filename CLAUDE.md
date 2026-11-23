# CLAUDE.md

ThirdMind: side process + knowledge store for a Logseq graph. Spec:
`design/knowos-via-logseq.md`. Persistence is Tunamelt
(`checkouts/tunamelt` symlink; `lein install` in `../tunamelt` so it resolves).

## Build & Test

```bash
lein test
lein run /opt/mt/repos/ammdi     # real graph; treat it as read-only
lein watch /opt/mt/repos/ammdi   # side process; Ctrl-C to stop
lein watch | lein serve | lein times   # with no args: graph, db, port etc. from config/ammdi.edn
lein blyg publish [--dry-run]    # #blyg items → the site repo's blyg dir (settings: config :blygger)
lein blyg push                   # commit + push only that dir
lein blyg mention                # webmentions for stubs now live
lein blyg preview                # #blyg items → target/blyg-preview.html (reads the db only)
```

## Layout

```
src/clj/hyperphor/thirdmind/
  parse.clj   — line-based Logseq markdown parser (names, properties, outline, refs)
  ingest.clj  — parsed graph → Tunamelt page/block/file objects; ref/alias resolution;
                update-file! (incremental, one file) and new-tags
  config.clj  — per-graph config (config/ammdi.edn): :graph :db :port :write? :blygger {...}
  core.clj    — -main: fresh db, ingest, report; outline, most-referenced
  search.clj  — `lein search`: t/search (FTS) hits grouped into pages
  watch.clj   — `lein watch [--write]`: poll graph, sync changed files, run tag handlers,
                (with --write) actions: detectors over each file's diff, and (with
                :blygger :auto-publish?) blyg publish + push once #blyg edits go quiet
  graph_write.clj — the only graph write path: wait for quiet, re-find block by content, atomic write
  smart_url.clj   — bare URL newly typed → [title](url)
  identity.clj    — block identity: match a page's old blocks to new by content
                    (`lein replay-ids <repo>` scores it on git history)
  ids.clj         — ThirdMind block ids (:tmid on block objects), in <db>.ids.db, which
                    rebuilds keep; one record per graph file, orphaned when the file goes
  blyg/content.clj — #blyg block → content_md / content_html (site links, privacy)
  blyg/wire.clj    — protocol 0.3 wire format (ids, hash, versions, tombstones, pins, JSON, feed);
                     pure: `surface` returns {path text}
  blyg/publish.clj — the publisher: #blyg blocks → state entries (by :tmid) → versions
  blyg/migrate.clj — import goddinpotty's .blyg-state.edn (by Logseq uuid) into ThirdMind's
  blyg/pages.clj   — archive and permalink HTML, filled into the live pages as templates
  blyg/remote.clj  — reply targets fetched (snapshots); webmentions sent
  blyg/cli.clj     — `lein blyg preview | import | publish | diff | push | mention | parity`
  ask_claude.clj  — `… #AskClaude` / `#AskClaudePage` → answer nested under a #AskClaude wrapper (claude.clj: API)
test/resources/graph — fixture graph covering parser edge cases
```

## Rules

- Every page URI goes through `ingest/page-key`; never build one by hand.
- Watcher tests copy the fixture graph to a temp dir.
   lein watch --write` actions (through graph_write.clj), and `lein blyg publish`'s  state files in the ammdi repo (`.thirdmind-blyg-state.edn`, `.thirdmind-ids.edn`), as  goddinpotty keeps `.blyg-state.edn` there.
