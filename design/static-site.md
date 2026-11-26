# Static site generation, incrementally (replacing goddinpotty)

Status: phases 1–3 done (inclusion decisions pending), not yet live. Branch `static-gen`. 2026-11-24.

## Goal

ThirdMind should generate the AMMDI site (`hyperphor-git/ammdi`) itself, from its own db.
When the watcher syncs a graph file, it should re-render only the output that file affects.
goddinpotty would then be retired. That is the "Level 1" publishing in `ui.md`, and the open
question at the end of `blygger.md`.

## Why

goddinpotty's build is the slowest and most fragile part of publishing:

- **Input.** It doesn't read the markdown. It reads the Datascript transit snapshot that
  only the running Logseq app writes. That snapshot goes stale (found 11 days behind), and
  the AppleScript that forces Logseq to save is broken (`goddinpotty/design/automated-publishing.md`).
  ThirdMind's db is synced from the files within seconds.
- **All or nothing.** Every build re-extracts the graph, re-renders ~1580 pages and their
  ~1580 map JSON files into a temp dir, then swaps the whole output dir. It runs daily from
  launchd, or by hand, so one edit can take up to a day to go live.
- **Two systems.** Blyg is already ThirdMind's (`blyg-cutover.md`), yet it still learns which
  pages are published by listing goddinpotty's output (`blyg/content/published-pages`), and
  goddinpotty needs `:preserve-dirs ["blyg"]` to keep from wiping it.

## What goddinpotty produces (the parity target)

All in `hyperphor-git/ammdi/`, 398 MB, mostly images:

| Output | Per | Depends on |
|---|---|---|
| `<slug>` (no extension), one HTML file per published page | page | see below |
| `assets/graphs/<slug>.json`, the Vega spec for the page's map widget (radius-2 neighbourhood) | page | the ref graph two hops out, and who is published |
| `index.html` → redirect to the main page | site | config |
| Generated pages: `Index` (ag-grid over `graphs/pages.json`), `Map` (whole-graph Vega) | site | every published page |
| `graphs/pages.json`, page rows for Index and Map | site | every published page (title, dates, ref counts) |
| `assets/index.js`, the elasticlunr search index (4 MB) | site | text of every published block |
| `assets/*.css, *.js, *.svg` | static | goddinpotty's `resources/public` |
| `assets/<image>` | per image | images referenced from published blocks, copied from the graph's `assets/` |
| `blyg/` | already ThirdMind's | unchanged |

A page is made of the navbar and footer (config), the title, the page's date range (file
edit times), the outline (exit-tagged subtrees removed, hover tags such as #AskClaude as
popups, sidenotes, `#WordCount`, macros, MathJax), and these sidebar widgets: About, Search,
Calendar (journals only), Page Contents (the TOC, when there are more than 2 headings), Map,
Page Tree (namespace hierarchy), Incoming links (the referring blocks, rendered) and Twin
Pages (an external fetch). There are also `og:` meta tags, analytics, and a hidden "Open in
Logseq" link.

### What's published

goddinpotty decides this with a reachability walk (`database/compute-depths`, `batadase`):

- **Entry points:** pages with `public:: true`, pages tagged `#EntryPoint`, the main page, and
  the navbar pages.
- **Walk:** breadth-first over refs from every included block, stopping at exit points. Exit
  points are the `:exit-tags`: `Private`, `ExitPoint`, `therapist`, `Tarot`, `cringe` and so on.
  A block (or page) is exit-tagged if it or one of its direct children carries an exit tag, and
  that hides it and everything under it. So an exit tag in *any* top-level block hides the whole
  page, and `- #Private` as the only child of a block hides that block. A `#Private` on `foo`
  also hides `foo/bar`.
- **#blyg items** (ThirdMind only, 2026-11-25): a block tagged `#blyg` (or `#blyg-pin`,
  `#blyg-reply`, `#blyg-response`; config `:entry-block-tags`) and its subtree, less anything
  exit-tagged, also start the walk, journal or not. They're public on the blyg already, so the
  pages they link to are published. This added 6 pages (Doom, Desire Management, Kabir, Old,
  Peter Principle, left-accelerationism).
- **Displayed:** the page must be included, and have content or children, or more than one
  included referrer.
- Journals aren't published (`:daily-notes? false`). Today 2900 pages and 2143 journals give
  ~1580 published pages.

This is global: one edit, such as adding `#Private` to a hub page or linking a new page from
a public one, can change the status of pages in other files.

## What ThirdMind already has

- **Data.** Pages and blocks with `:refs`, `:tags`, `:parent`, `:order` and aliases in Tunamelt.
  `t/referrers` gives backlinks, `t/search` gives FTS, and `times.clj` gives per-block
  created/updated times from git blame.
- **Change feed.** `watch/sync!` calls `ingest/update-file!`, which returns
  `{:page uri :old [...] :new [...]}` for each changed file. This is the incremental input.
- **Rendering.** `ui/render.clj` turns block markdown into HTML (refs, tags, block refs,
  macros, images), and `ui/pages.clj` has the outline, backlinks, breadcrumb, date range,
  index rows and the AskClaude `<details>`. Both target `lein serve`.
- **Privacy and site links.** `blyg/content.clj` has `slug` (goddinpotty's URL names),
  `private?` (exit tags), `page-url` (link only to published pages) and media handling.
- **Writing a site dir.** `blyg/publish.clj` and `lein blyg push` (commit and push only the
  mount dir), plus the watcher's quiet-then-publish loop (`auto-publish!`).

## Design

### 1. Inclusion as a ThirdMind computation

`site/include.clj` computes `{:pages #{page-uri} :hidden #{block-uri}}` by goddinpotty's rules,
over an in-memory ref graph built from the db: block → refs, block → children, page → blocks,
and namespace parent → children.

The whole walk is recomputed after every sync pass. It's a BFS over maybe 100k edges, held in
memory and patched from each file's diff, so it takes milliseconds. That's far simpler than
maintaining reachability incrementally, and not slower in any way that matters. The result
is diffed against the previous run: **newly published**, **newly unpublished**, and
**newly hidden or unhidden blocks**.

This becomes the one published set. `blyg/content` takes it in place of
`published-pages`, so blyg and site links always agree.

### 2. A renderer whose output is a function of the db

`site/render.clj` has one function per output, `(render-page ctx page-uri) → {path bytes}`,
plus one each for the site-wide files. It reuses `ui/render` and `ui/pages`, with
`blyg/content`'s rules for links (`page-url`), privacy and assets. Rendering a page reads only
the db and the inclusion result, so it gives the same bytes every time for the same inputs.
In particular, no build timestamps (goddinpotty already took out its "Exported" line for this
reason).

The look stays goddinpotty's at first: the same HTML structure and class names, with its
`assets/*.css`/`*.js` copied over unchanged, so the switch can be checked by diff. A new
look is a separate project (the ThirdMind plain look in `ui.md` is for `lein serve`).

### 3. Dependencies recorded during rendering

Instead of hand-written invalidation rules ("a change to P also affects the pages P links
to…"), each render records what it read. The render ctx wraps `fetch`, `referrers` and
`find` so that each call records a key:

- `[:obj uri]` for an object read (page, block, or a block transcluded via `((ref))`).
- `[:refs-to uri]` for a backlinks query (Incoming links, the referrer counts behind
  "displayed").
- `[:children uri]` for an outline walk.
- `[:published uri]` for each "is this page published / what's its slug" check behind a link.

Each output path gets a stored set of keys (`deps` table, in the ThirdMind db, not in the
graph repo), with a reverse index from key to paths.

After a sync pass, a file's diff produces **change keys**:

- `[:obj u]` for every old and new block uri, and for the page.
- `[:children p]` for every parent whose children changed.
- `[:refs-to t]` for every target `t` in the symmetric difference of a block's old and new
  refs. A changed block also yields `[:refs-to t]` for all its current targets, because the
  Incoming links widget shows that block's text.
- `[:published u]` for every page in the inclusion delta.

The paths to re-render are the outputs whose deps meet the change keys, plus the pages that
were just published, while files for pages that were just unpublished are deleted. Each
re-rendered file is written only if its bytes changed, which keeps git diffs and pushes small.

Recording catches dependencies nobody thought of: an alias that changes a link's target, a
block ref into another page, a namespace page appearing. It errs on the side of re-rendering
too much, which is the safe direction.

### 4. The expensive dependents: map and site-wide files

- **Map widget.** Its JSON depends on the ref graph two hops out, so recorded deps would
  pull in a large neighbourhood on every edit, and a hub page's edit would rebuild hundreds
  of map files. The proposal is to stop baking it per page. Write the global graph once (the
  `graphs/pages.json` edges, which the Map page already needs) and compute the radius-2 view
  in the browser (a small change to the embed script). That removes ~1580 files, and the
  global file is rewritten only when the edge set or the published set changes. If an exact
  match with the old maps matters more, keep per-page specs and let recorded deps handle
  them, accepting the cost.
- **Search index, `pages.json`, Index, Map.** These depend on everything. They're rebuilt
  after a quiet period (the same debounce as blyg publishing) whenever any page changed, and
  written only if different. The search index can be built per page and joined, so a rebuild
  re-tokenises only the changed pages.
- **Twin Pages.** This fetches an external service. Cache its result per page with a long
  TTL, so it never causes a re-render.

### 5. Privacy is the overriding constraint

An incremental build that misses a dependency has two possible failure modes. If it shows
something stale, that's harmless. If it shows a block that has just been made private, that's
a leak. So:

- **Any exit-tag change triggers a full render.** That means a change to an exit-tag ref
  anywhere in a diff, or a change in `:hidden`. Exit-tag edits are rare, so the cost is fine,
  and it means correctness on privacy never depends on recorded deps.
- Unpublished pages are deleted in the same pass that detects them, before any push.
- **Push after a quiet period, like blyg.** Pushing is the moment content becomes public.
  Writing to the local output dir publishes nothing.
- A periodic full verify, nightly or `lein site verify`, renders everything into a temp dir
  and diffs it against the live output dir. Any difference is a bug in the dependency
  recording. It gets logged with the paths, and the full render's output is written.

### 6. The output dir and deploying

ThirdMind writes into `hyperphor-git/ammdi` in place: write-if-changed and delete-if-gone,
with no temp-dir swap. It owns the whole tree except what it copies verbatim (`assets/`
statics, images). `lein blyg push` becomes `lein site push` over `ammdi/`: commit only that
dir, then `git push website`. In the watcher it's the same quiet-period loop as auto-publish,
under a config flag (`:site {:auto-publish? …}`).

Images: copy each image referenced from a published block, under its current name, whenever
it's newly referenced, and delete it once nothing published references it (a `[:published]`
dep in the other direction). Use the same names goddinpotty uses, so no URLs change.

### 7. Commands

```
lein site build [--out dir]   # full render; write-if-changed (default: config :site :output-dir)
lein site diff                # what a full render would change, without writing
lein site parity <gp-dir>     # page set + normalised HTML diff vs goddinpotty's output
lein site verify              # full render to temp, diff against live (incremental check)
lein site push                # commit + push only the site dir
```

The watcher gains a site action next to the blyg one.

## Phases

1. **Inclusion parity.** Port the walk. Compare ThirdMind's published set with the ~1580 file
   names in goddinpotty's output, and explain or fix every difference. Nothing else in the
   design matters until this is exact, because privacy rides on it.
2. **Full renderer.** Page template and widgets, assets, images. `lein site parity` compares
   HTML normalised for whitespace and attribute order. Differences are grouped by kind (the
   way `lein blyg parity` reports them) and either fixed or accepted on purpose.
3. **Site-wide files.** Index, Map, `pages.json`, search index, and the client-side map
   widget.
4. **Incremental.** Dependency recording, change keys, the watcher action, write-if-changed,
   deletes, the full-render trigger on privacy changes, and `verify`. The test invariant is
   that for any edit sequence over the fixture graph, the incremental output equals a full
   build's.
5. **Cutover.** Point blyg at the new published set, drop goddinpotty's `:preserve-dirs`,
   stop goddinpotty's daily launchd job, and have ThirdMind write `ammdi/`. Keep goddinpotty
   buildable into a scratch dir for a while, for comparison.
6. **The private site** (`:unexclude? true`, laptop-local, never pushed) as a second site
   config. It's the same code with inclusion turned off, written to its own dir.

## Phase 1 results (2026-11-23)

`site/include.clj` ports the walk; `lein site parity` compares it with goddinpotty's output
(`lein site why <page>` shows the path that published a page, or that it's an exit point).
Against the 22 Nov build: 1570 pages vs 1578, matched ignoring case (macOS file names are
case-insensitive). Every remaining difference is explained:

Fixed in ThirdMind along the way (each changes the db, not just the site):

- **Legacy file names.** The graph has no `:file/name-format`, so Logseq reads `.` in a file name
  as `/` (`goddinpotty.TODOs.md` is `goddinpotty/TODOs`; `R. Crumb.md` is `R/ Crumb`) unless
  `title::` says otherwise. `parse/file-page-name` now does too.
- **Aliases don't shadow file pages.** `psychoanalysis` has `alias:: Freud`, and `Freud.md`
  exists: `[[Freud]]` is the Freud page. An alias naming a page with its own file is now a walk
  edge between the two pages, not a redirect.
- **Text before the first bullet** (`# Stalking` on Jacob Foster) is a block that indented
  bullets nest under, as in Logseq. Before, its `#Private` child became a top-level block and
  hid the whole page.
- **Exit tags after punctuation**, as in `(#Private)` on `me`: Logseq doesn't link these, so
  they aren't refs, but the walk counts them as exit tags (`parse/loose-tags`), as goddinpotty's
  parser did.

Only goddinpotty's (20):

- Pages that exist only in Logseq's db, as an empty block Logseq adds when you visit a page
  with no file: Fred Turner, I Am a Strange Loop, Kircher, Marshall McLuhan, Projects. Also
  `Obsidian.md` at the graph root, outside `pages/`.
- `.org` files, which ThirdMind doesn't read: `pages/doc/{node,pici,r,typescript}.org`, `org-mode
  test`, At the Existentialist Cafe, Rage and Time. `pici` is the namespace parent of the 5
  `PICI/*` pages, and `#pici` is how `research engine` is reached.
- The Ministry for the Future: a top-level `- #Scrap` block. goddinpotty matches exit tags
  case-sensitively, so `#Scrap` missed `scrap`, and only the `#Private` under it applied.
  ThirdMind matches it, and by the top-level-block rule that hides the whole page.

Only ThirdMind's (12):

- Edited or created since the snapshot: Dafny, commitment.
- Files Logseq's db had lost track of (no file, no blocks): non-grasping, ...and forgive them their debts.
- Links inside `{{sidenote …}}`, which neither Logseq nor goddinpotty counts: Complice, anatman.
- Links in property values (`Topics:: [[schizophrenia]]`), which Logseq counts and goddinpotty's
  parser didn't: the three Zotero-import papers.
- John McCarthy: Logseq's `lisp` is `pages/doc/lisp.org`, which collides with `Lisp.md`. ThirdMind
  uses `Lisp.md`, which links to him.
- Crumb and tathagatagarbha: the alias edge above.

Case only (9): Logseq keeps the case of a page's first mention (`claude`), ThirdMind the file
name (`Claude`). It doesn't matter on macOS, and on the server it matters only if links use the
old case.

**Blocks.** `lein site parity --blocks` takes the first words of every block we'd show on a
page both sides publish, and looks for them in goddinpotty's outline (not its sidebar, whose
incoming links quote other pages). 85 misses, none under an exit tag: the Lisp/Emacs `.org`
collisions, pages edited since the snapshot, the LWMap pages' stray `---` front matter, and
rendering differences (sidenotes splitting a sentence, hiccup tables, raw HTML, bare URLs,
`#AskClaude` prompts shown as hover popups). Phase 2 repeats this against our own HTML.

**The live system.** The parser and alias changes rename pages and move refs, and `lein watch`
only re-ingests changed files, so `ammdi.db` must be rebuilt (deleted; its ids store is kept)
when the watcher restarts on this code. Until then `lein site parity` without `--db` reads the
old rules. With a rebuilt db, `lein blyg publish --dry-run` bumps 5 items, all because
`[[Society of Mind]]` now links to `Marvin-Minsky∕Society-of-Mind` (the page goddinpotty
links to) rather than a nonexistent `Society-of-Mind`. Re-versioning the Orality and Literacy
thread also drops two empty blocks left over from the goddinpotty migration.

Not ported: `public:: true` as an entry point. goddinpotty checks `:public?`, but nothing ever
sets it.

## Phase 2 results (2026-11-23)

`site/render.clj` renders every published page in goddinpotty's markup, and `build!` writes them,
`index.html`, goddinpotty's statics and the images published blocks use. `lein site build`
writes to `target/site` (config `:site :build-dir`) and refuses the live site dir before
cutover. A rebuild with nothing changed writes nothing (contents compared; images by size and
mtime), and a manifest in the output dir (`.thirdmind-site.edn`) lets a build delete what the
last one wrote and this one doesn't.

- **Blocks** go through `ui/render` (commonmark), with hooks added for the site: `pagelink`
  links, `<span class="empty">` for unpublished pages, tags without `#`, goddinpotty's sidenote
  and `#AskClaude` hover markup. Block refs and embeds show their target only if it's published.
  Block properties render as goddinpotty's `<li><b>key</b>: value</li>`.
- **Anchors** are a block's `id::` where it has one (goddinpotty's anchors, which blyg links
  use), else its ThirdMind id.
- **Widgets:** About, Search, Page Contents, Page Tree, Incoming links, Twin Pages. Calendar is
  journals-only, so not needed. Map is phase 3.
- **Search index and Index page** (pulled forward from phase 3): `assets/index.js` has a row per
  published page (url, title, aliases, the outline's text, date of the latest edit, depth from
  an entry page, size). search.js and the Index page's grid.js both read it. Left for phase 3:
  the Map widget, the Map page and `graphs/pages.json`.
- **Dates** come from block times (`times.clj`), over the page's shown blocks.
- **Fetch caches:** Twin Pages and tweet embeds read goddinpotty's `.enduro.d` caches (read
  only; config `:twin-cache`, `:tweet-cache`). Pages and tweets not in them get no Twin Pages
  card, and a client-side tweet embed. Fetching new ones is still to do.

`lein site parity --html` compares each built page's outline words with goddinpotty's: median
overlap 0.998 over 1558 pages, 111 below 0.9. Those are the phase 1 differences (the `.org`
collisions, pages edited since the snapshot), tweets missing from the embed cache, pages with
nothing but properties (where only the dates differ), and the items below.

It also checks for leaks across the whole site. Every block the walk left out (journals,
unpublished pages, hidden blocks) is matched against the full body of every built page,
sidebar included, first on its first 3 to 6 words, then on its first 15. Text that a published
block or page title also contains doesn't count. 6 hits remain, all text that is also public:
two quote tweets we embed, two are an Incoming links "from <page>" label run into a published
block, one is a book title listed on both a private and a public page, and one is a Twitter
account name. A reference to an unpublished block renders as nothing (it used to show the
block's id).

The `ui/render` change also reaches blyg: a transclusion no longer gets a "↗" link to a
`/block/…` URL the site doesn't have, so blyg items quoting a block get a new version the next
time they publish (2 at present).

- goddinpotty's special tags (`#WordHistory` charts, `#WordCount`) aren't ported.
- No `<!DOCTYPE html>`, as goddinpotty, so the CSS behaves the same (quirks mode).
- Page Tree sorts case-insensitively rather than by numeric prefix.

## Phase 3 results (2026-11-24)

The search index and Index page landed in phase 2. The maps are now one site-wide graph,
`graphs/map.json` (170 KB: 1568 pages, 4307 links), in place of goddinpotty's ~1580 per-page
specs. Nodes are published pages by name, except `:graph-exclude` tags (default AskClaude, book,
influence). Links come from shown blocks' refs and namespace parents, with each node's
neighbours sorted. `resources/site/map.js`, copied to `assets/map.js`, draws them with
goddinpotty's Vega force spec. The sidebar map is the page's radius-2 neighbourhood, following at
most 8 neighbours per node; the Map page shows everything, entry pages highlighted. One edit
now rewrites one 170 KB file instead of up to hundreds of map files.

Differences: goddinpotty followed an arbitrary 8 of a hub's neighbours, ThirdMind the first 8 by
name. `graphs/pages.json` isn't written: only goddinpotty's `#dataviz1` tag read it, and that's
unported along with `#WordHistory`.

## Open questions

- **How exact is "parity"?** Byte-identical with goddinpotty is unrealistic and not worth
  the effort (Hiccup output differences, sidenote markup). Is "same pages, same URLs, same
  visible content, CSS still works" the bar? 
  Answer: yes, and it doesn't even have to be that exact, not like the old look is sancrosanct

- **Map widget.** Is moving to a client-side neighbourhood (one global file) acceptable, or
  do the per-page specs stay?  
  Answer: Sure lets try it that way
  
- **Date ranges.** goddinpotty uses file edit times (`edit_times`). ThirdMind has per-block
  git-blame times (`times.clj`), which are more accurate but will change the dates shown.
  Use ThirdMind's?
Yes

- **Journals.** They're excluded today. Should a journal block that reaches an entry point
  (through a public page linking to it) still be dropped?
  Yes
-
 **#AskClaude → #AskedClaude** (`ask-claude-tags.md`). The hover rendering keys on the tag.
  It's easier to land the tag split first, or to have the renderer accept both.
 What's the question? 

- **Where do dependencies live?** In the ThirdMind db (rebuilt with it, so a db rebuild
  forces a full site render), or in a separate file like the ids db?
Definitely in the thirdmind db, 

- **Recent changes page.** It's broken in goddinpotty for Logseq. ThirdMind has block times,
  so it could come back. In scope?
Sure lets get that back.
