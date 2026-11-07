# ThirdMind

A side process and knowledge store for a Logseq (or any markdown-outline) graph. Logseq
stays the UI; ThirdMind keeps its own database of the graph in [Tunamelt](../tunamelt),
watches for changes, and responds to tags (`#AskClaude`, `#Goal`, …).
Spec: `design/knowos-via-logseq.md`.

Current state: builds the database from a graph, keeps it in sync as files change, and
logs newly tagged `#blyg` blocks as a first tag handler.

```bash
lein run /opt/mt/repos/ammdi            # writes ammdi.db (realm "ammdi") from scratch, reports on it
lein run <graph-dir> [db-path]
lein watch /opt/mt/repos/ammdi [db-path]   # side process: keep the db in sync, run tag handlers
lein search heidegger [db-path] [--snippets] [--raw]   # pages matching via Tunamelt full-text search
```

## Data model

Pages and blocks are Tunamelt objects; relations are attributes holding URIs.

| type | uri | attributes |
|---|---|---|
| page | `hyperphor://<realm>/page/<encoded lowercase name>` | `:name :file :journal :journal-day :blocks :tags :alias :prop/*`; `:stub true` for pages only referenced |
| block | `hyperphor://<realm>/block/<id:: or page~outline.path>` | `:content :page :parent :order :depth :refs :tags :block-refs :marker :prop/*` |
| file | `hyperphor://<realm>/file/<encoded rel path>` | `:path :mtime :size :page` — what the watcher last ingested |

```clojure
(t/find "block" {:attrs {:tags page-uri}})          ; blocks tagged #foo
(t/find "block" {:attrs {:marker "TODO"}})
(t/referrers page-uri {:attr :refs})               ; backlinks
(core/outline page-uri)                            ; outline rebuilt from :parent/:order
```

## Watcher

`lein watch` polls `pages/` and `journals/` every 2s, comparing each file's mtime and size
with the db's file records. It skips files modified within the last interval (Logseq
saves while you type). It re-ingests each changed file on its own, with aliases and block
ids resolved against the db, and removes the pages of deleted files. A deleted page that's
still referenced reverts to a stub. On start it catches up on changes made while it was
stopped. A db with no file records (new, or built before the watcher existed) is rebuilt from scratch,
which takes about 45s for ammdi. After that, a typical file syncs in about 0.1–0.4s.

Tag handlers (`watch/default-handlers`, tag name → fn of the block) run when a block
newly carries the tag: a tagged block counts as new unless a tagged block on that page
before the change had the same uri or content. Inserting blocks above it, which renumbers
position-based ids, doesn't re-fire it. Editing a tagged block's text does. Handlers
don't fire during a full rebuild.

Known limits:
- Two files whose pages share a name overwrite each other's blocks.
- Changing an `alias::` doesn't re-resolve refs in other pages until those files change.
- Block refs to blocks in a file not yet synced are dropped.

## Parsing notes

- Legacy file names: `%XX` decoded; `title::` overrides. Journals (`yyyy_MM_dd.md`) are
  named like Logseq's default, `Jul 5th, 2013`.
- Page refs are case-insensitive and resolve through `alias::`.
- Refs inside fenced/inline code are ignored; fenced `- ` lines are content, not blocks.
- Blocks without `id::` get position-based ids, so `lein run` rebuilds the database fresh and
  the watcher replaces a changed file's blocks wholesale.
- Only `.md` directly under `pages/` and `journals/` is read (no subdirectories, no `.org`).
