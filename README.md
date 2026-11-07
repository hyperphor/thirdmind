# ThirdMind

A side process and knowledge store for a Logseq (or any markdown-outline) graph. Logseq
stays the UI; ThirdMind keeps its own database of the graph in [Tunamelt](../tunamelt),
and will eventually watch for changes and respond to tags (`#AskClaude`, `#Goal`, …).
Spec: `hyperphor/stack/knowos-via-logseq.md`.

Current state: reads a graph into a fresh database and reports what it looks like.

```bash
lein run /opt/mt/repos/ammdi            # writes ammdi.db (realm "ammdi")
lein run <graph-dir> [db-path]
lein search heidegger [db-path] [--snippets] [--raw]   # pages matching via Tunamelt full-text search
```

## Data model

Pages and blocks are Tunamelt objects; relations are attributes holding URIs.

| type | uri | attributes |
|---|---|---|
| page | `hyperphor://<realm>/page/<encoded lowercase name>` | `:name :file :journal :journal-day :blocks :tags :alias :prop/*`; `:stub true` for pages only referenced |
| block | `hyperphor://<realm>/block/<id:: or page~outline.path>` | `:content :page :parent :order :depth :refs :tags :block-refs :marker :prop/*` |

```clojure
(t/find "block" {:attrs {:tags page-uri}})          ; blocks tagged #foo
(t/find "block" {:attrs {:marker "TODO"}})
(t/referrers page-uri {:attr :refs})               ; backlinks
(core/outline page-uri)                            ; outline rebuilt from :parent/:order
```

## Parsing notes

- Legacy file names: `%XX` decoded; `title::` overrides. Journals (`yyyy_MM_dd.md`) are
  named like Logseq's default, `Jul 5th, 2013`.
- Page refs are case-insensitive and resolve through `alias::`.
- Refs inside fenced/inline code are ignored; fenced `- ` lines are content, not blocks.
- Blocks without `id::` get position-based ids, so each run rebuilds the database fresh.
- Only `.md` directly under `pages/` and `journals/` is read (no subdirectories, no `.org`).
