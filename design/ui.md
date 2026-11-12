General idea: display the graph as Logseq/goddinpotty would

Eventually maybe replace Logseq, although that is not a priority This should work on any collection of markdown files

# Separate system?

I can't decide whether to make this a separate system or not. Getting too many different parts here.

Also it starts to overlap with KnowOS/Repl. Argh. Well do it integrated for now, always possible to split later.

Integrated, but kept at arm's length: new namespaces under `hyperphor.thirdmind.ui.*` that only
read the Tunamelt db (never parse.clj directly), so splitting later means moving one directory.

# Architecture

- **Live server, not a static generator (at first).** A small server renders each page on
  request from the Tunamelt db. `lein serve` is a separate process from `lein watch`; both use the
  same SQLite db, so with the watcher running the display stays fresh. Level 0 is incremental for
  free; static output is a Level 1 concern.
- **Stack:** http-kit + plain routing + Hiccup 2. No JS framework at Level 0; plain
  server-rendered HTML and one CSS file, with a fresh plain look (not goddinpotty's).
- **Data source:** everything comes from the db (`t/find`, `t/get`, `t/referrers`, `t/search`).
  Page outlines rebuild from `:parent`/`:order` like `core/outline`. This exercises the db as the
  system of record, which is the point.
- **Rendering block content:** blocks store raw Logseq markdown in `:content`. Need an inline
  renderer that handles: `[[wikilinks]]`, `[[alias|label]]`?, `#tag` / `#[[multi word]]`,
  `((block refs))` (transclude content), `**bold**`/`_italic_`/`` `code` ``, URLs and
  `[text](url)`, images, TODO/DONE markers, `key:: value` properties. Options:
  1. commonmark-java with a custom inline extension for wikilinks/tags/block refs.
  2. Port goddinpotty's instaparse grammar (`resources/parser.ebnf`) + `rendering.clj`.
  3. A small hand-rolled regex tokenizer, reusing the patterns in `parse/extract-refs`.
  **Decided: (1).** Logseq syntax is rewritten to inline HTML links (outside code) before
  commonmark sees it, using the same ref regexes as `parse/extract-refs` so rendered links always
  agree with stored refs. Link resolution goes through `ingest/page-key` and alias resolution.
  Raw HTML passes through, fine locally; revisit for Level 1 publishing.

# Browsing

## Level 0 - Basic browsing

HTML generation
Page navigation
Links work
Backlinks work

Routes:

| Route              | Shows                                                                      |
|--------------------|----------------------------------------------------------------------------|
| `/`                | index: all pages, sortable by name / ref count / recent; journals separate |
| `/page/<page-key>` | page title, page properties, block outline (nested `<ul>`), backlinks      |
| `/block/<id>`      | a single block in context (breadcrumb to page), for block-ref links        |
| `/search?q=`       | `t/search` hits grouped by page (reuse search.clj)                         |
| `/static/*`        | CSS                                                                        |

Details:
- Stub pages (referenced but no file) render with just backlinks, styled as "empty" links.
- Backlinks: `t/referrers page-uri {:attr :refs}`, grouped by referring page, each showing the
  referring block with its ancestor breadcrumb. Separate section for unlinked refs is a later nicety.
- Aliases: `/page/<alias-key>` redirects (or renders) the canonical page.
- Journals: show date title; index lists them newest first.
- Collapsed blocks (`collapsed:: true`) render collapsed via `<details>`.

Steps:
1. `ui/render.clj`: block content → Hiccup, with unit tests against the fixture graph's edge cases.
2. `ui/pages.clj`: page, index, search views (pure fns db → Hiccup).
3. `ui/server.clj`: routes, `-main`; `lein serve <graph-dir> [db-path]` (read-only on the db).
4. Try it against ammdi; fix whatever the real graph breaks.

Status (Nov 12): steps 1–3 done; first pass against ammdi works. Pages with ~1.5k backlinks take
~2s. Not yet: page embeds ({{embed}}), sort-by-refs on the index, unlinked refs.

### Level 0.1
 -Better styling. 
 - blockquotes need to be distinguished
 - bullets subtler
 - better fonts/background. Will experiment, start by something like this: https://blyg.existingotherwise.org/
 - pages should have link to Logseq (logseq:// whatever, goddinpotty does this in local mode)
- index should be like ammdis (sortable multicol grid)

Status (Nov 12): done, plus images (`../assets/` served from the graph's assets/ dir, `{:width}`
honoured) and Logseq-style embedding of blocks that are only a `((ref))`. Index is ag-grid 33 over
`/index.json` (title, kind, modified, blocks, refs), cached until the db changes; ~1.8s to build on
ammdi, then instant.

### Level 0.2
- blockquotes need more distinguishing (smaller font, different background)
- Anything under #AskClaude should be also rendered as quote (and maybe closed by default)
- don't render markdown metadata like -- title
- skip empty blocks
- render {{twitter}} and {{video}} (copy from goddinpotty if necessary)
- titles like __Erlebnis__ should render in italics

Status (Nov 12): done. Front matter (`---` / `key: value` / `---`) is parsed into page props by
parse.clj, so a re-ingest is needed for existing dbs. `__x__` renders as italics everywhere (Roam
convention; commonmark would make it bold). Macros: tweet/twitter (Twitter's widgets.js, loaded
only on pages with tweets), video/youtube (YouTube, Shorts, Vimeo), embed ((id)) as a transclusion,
embed [[page]] as a link (full page embed not yet), sidenote inline; others shown as-is.

### Level 0.3
- images need an expand / popout affordancve of some sort

### Times
OK, this one is more complicated. I want blocks annotated with their
time range (creation - last update). Visually this should be subtle, computationally it might need to be derived from github history and stored in db

Also pages should show a time range (goddinpotty already does this)

And on journal pages include the day of the week


## Leval 1 - Incremental publishing

Privacy logic
Derived Goddinpotty features work (eg graph, page trees, lots of misc features).
Needs some kind of dependency management
Goal: incremental publishing

- **Static export:** walk the same page views and write HTML files; the server and the exporter
  share all rendering code.
- **Privacy:** goddinpotty's entry/exit-point model (`#EntryPoint`, `#ExitPoint`/`#Private`)
  computes an `:include?` set. Local browsing shows everything; publishing filters by it.
- **Dependency management:** a page's HTML depends on its own blocks, on the pages its links point
  to (stub vs real, titles), on its backlinks, and on any transcluded blocks. The watcher's
  `update-file!` diff already tells us which page URIs changed; mark dirty = changed pages + pages
  that reference or are referenced by them (via `:refs`). Derived pages (index, graph map) are
  dirty on any page add/remove/rename. Inclusion changes may cascade widely; recompute fully then.
- **Derived features to port, roughly in priority order:** client-side search, recent
  changes, page graph map, sidenotes, image copying, twin pages.

# Level 2 - editing

Actually replace Logseq
Use Blocknote as UI component (need to get wikilinks working)

- Writes go to the markdown files (the files stay the source of truth), then the watcher picks them
  up like any other edit. Note this is the first time ThirdMind writes to a graph; the
  "never write to the source graph" rule needs revisiting, at least as an opt-in.
- Needs a markdown ⇄ Blocknote block mapping that round-trips Logseq's outline and properties
  without churn.
