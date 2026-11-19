# CLAUDE.md

ThirdMind: side process + knowledge store for a Logseq graph. Spec:
`design/knowos-via-logseq.md`. Persistence is Tunamelt
(`checkouts/tunamelt` symlink; `lein install` in `../tunamelt` so it resolves).

## Build & Test

```bash
lein test
lein run /opt/mt/repos/ammdi     # real graph; treat it as read-only
lein watch /opt/mt/repos/ammdi   # side process; Ctrl-C to stop
lein blyg preview /opt/mt/repos/ammdi --goddinpotty ../../goddinpotty/resources/ammdi-config.edn
                                 # #blyg items → target/blyg-preview.html (reads the db only)
```

## Layout

```
src/clj/hyperphor/thirdmind/
  parse.clj   — line-based Logseq markdown parser (names, properties, outline, refs)
  ingest.clj  — parsed graph → Tunamelt page/block/file objects; ref/alias resolution;
                update-file! (incremental, one file) and new-tags
  core.clj    — -main: fresh db, ingest, report; outline, most-referenced
  search.clj  — `lein search`: t/search (FTS) hits grouped into pages
  watch.clj   — `lein watch [--write]`: poll graph, sync changed files, run tag handlers
                and (with --write) actions: detectors over each file's diff
  graph_write.clj — the only graph write path: wait for quiet, re-find block by content, atomic write
  smart_url.clj   — bare URL newly typed → [title](url)
  identity.clj    — block identity: match a page's old blocks to new by content
                    (`lein replay-ids <repo>` scores it on git history)
  ids.clj         — ThirdMind block ids (:tmid on block objects), in <db>.ids.db, which
                    rebuilds keep; one record per graph file, orphaned when the file goes
  blyg/content.clj — #blyg block → content_md / content_html (site links, privacy)
  blyg/cli.clj     — `lein blyg preview`; site URL, output dir, exit tags from goddinpotty's config
  ask_claude.clj  — `… #AskClaude` / `#AskClaudePage` → answer nested under a #AskClaude wrapper (claude.clj: API)
test/resources/graph — fixture graph covering parser edge cases
```

## Rules

- Every page URI goes through `ingest/page-key`; never build one by hand.
- Never write to the source graph. Watcher tests copy the fixture graph to a temp dir.
