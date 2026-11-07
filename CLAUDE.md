# CLAUDE.md

ThirdMind: side process + knowledge store for a Logseq graph. Spec:
`design/knowos-via-logseq.md`. Persistence is Tunamelt
(`checkouts/tunamelt` symlink; `lein install` in `../tunamelt` so it resolves).

## Build & Test

```bash
lein test
lein run /opt/mt/repos/ammdi     # real graph; treat it as read-only
lein watch /opt/mt/repos/ammdi   # side process; Ctrl-C to stop
```

## Layout

```
src/clj/hyperphor/thirdmind/
  parse.clj   — line-based Logseq markdown parser (names, properties, outline, refs)
  ingest.clj  — parsed graph → Tunamelt page/block/file objects; ref/alias resolution;
                update-file! (incremental, one file) and new-tags
  core.clj    — -main: fresh db, ingest, report; outline, most-referenced
  search.clj  — `lein search`: t/search (FTS) hits grouped into pages
  watch.clj   — `lein watch`: poll graph, sync changed files, run tag handlers
test/resources/graph — fixture graph covering parser edge cases
```

## Rules

- Every page URI goes through `ingest/page-key`; never build one by hand.
- Never write to the source graph. Watcher tests copy the fixture graph to a temp dir.
