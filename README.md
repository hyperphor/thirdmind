# ThirdMind

ThirdMind runs beside a [Logseq](https://logseq.com) graph (or any folder of Logseq-style
markdown outlines). It keeps its own database of the graph in
[Tunamelt](https://github.com/hyperphor/tunamelt) and keeps it in sync as you edit. On that
database it can:

- publish the graph, or the parts of it you mark public, as a static website;
- publish blocks tagged `#blyg` to a [blyg](design/blygger.md) (a microblog of fragments, with
  an RSS feed and webmentions); for now this needs goddinpotty as well (see [Blyg](#blyg));
- answer questions you tag `#AskClaude`, writing Claude's answer back into the graph;
- turn bare URLs you type into `[title](url)` links;
- serve a local browser view of the graph.

Logseq, or another editor, stays the place you write. ThirdMind only reads the graph, except
for `#AskClaude` answers and smart URLs, which are off unless you turn them on.

Status: working and in daily use on one large graph (about 5000 pages), but young. Expect
rough edges, and read [Limits](#limits) before relying on it.

## Requirements

- Java 21 or later
- [Leiningen](https://leiningen.org)
- A Logseq graph in markdown: a folder with `pages/` and `journals/`

Clone this repository and run everything from its top directory. Dependencies, Tunamelt
included, come from Clojars on first run.

## Publish a graph as a website

1. Copy the example config and edit it:

   ```bash
   cp config/examples/site.edn config/mysite.edn
   ```

   At minimum set `:graph` to your graph's folder, and `:main-page` inside the `:site` map to
   the page that should be the front page. The [configuration](#configuration) section lists
   every setting.

   For the site's stylesheet, search box and page index you also need five files from
   goddinpotty; see [Styling and search](#styling-and-search). The site builds without them,
   but looks unfinished.

2. Build:

   ```bash
   lein site build
   ```

   The first run reads the whole graph into a database (`<graph folder name>.db` in the current
   directory), which takes a few seconds for a small graph and under a minute for a large
   one. Later runs re-read only the files that changed, then rewrite only the pages whose HTML
   changed. The site goes to `:build-dir` (default `target/site`).

3. Copy the build directory to your web host. If the host is GitHub Pages, Netlify or
   Cloudflare Pages, set `:page-suffix ".html"` (see [Page files](#page-files)).

   To look at the site locally, set `:page-suffix ".html"` and use a static server that serves
   `/Foo` from `Foo.html`, such as `npx serve target/site`. (`lein serve` is a different thing:
   a live view of the whole graph, not the built site.)

Commands look for the config in this order: a path given as the first argument
(`lein site build config/mysite.edn`), then `$THIRDMIND_CONFIG`, then the only `.edn` file
directly inside `config/`. Files in `config/examples/` don't count.

### What gets published

Nothing is published unless something leads to it. ThirdMind starts from these pages:

- the main page, and any pages in `:entry-pages`;
- pages with the property `public:: true`;
- pages tagged with one of `:entry-tags` (default `#EntryPoint`);
- blocks tagged `#blyg`, `#blyg-pin`, `#blyg-reply` or `#blyg-response` (the [blyg](#blyg)
  tags; `:entry-block-tags` changes the list), with their children.

From there it follows links, in both directions (links out of a page, and pages that link
in), and namespace parents (`a/b` reaches `a`). It stops at:

- journal pages, which are never published;
- anything carrying an **exit tag** (`:exit-tags`, default `["Private"]`). A block tagged
  `#Private`, or with a `#Private` child, is left out along with everything under it. An exit
  tag in a top-level block hides the whole page, and hiding `foo` also hides `foo/bar`.

With the default exit tags, `#Private` is how you keep something off the site. Because links
are followed in both directions, a page is published if any published page links to it, or if
it links to a published page. Check before you upload the site:

```bash
lein site published --list          # every page that would be published
lein site why "Some Page"           # whether it's published, and the chain of links that reached it
```

Links to unpublished pages render as plain text, and blocks hidden by an exit tag are left
out of every page, including the "Incoming links" list in each page's sidebar.

### Page files

Each page is written as a file named after the page (`Computer Power and Human Reason` becomes
`Computer-Power-and-Human-Reason`), and links between pages use those names with no
extension. The server must send these files as `text/html`, which most static hosts won't
do for a file with no extension. With `:page-suffix ".html"` the files get an `.html`
suffix and the links stay the same, which suits hosts that serve `/Foo` from `Foo.html`
(GitHub Pages, Netlify, Cloudflare Pages). The build also writes an empty `.nojekyll`, without
which GitHub Pages would drop pages whose names start with `_`.

Besides the graph's pages, the build writes `index.html`, which redirects to the main page,
and two generated pages: `Index`, a sortable table of all pages, and `Map`, a graph of the
whole site. With `:page-suffix ".html"` the Index page is written as `All-Pages.html`, since
`Index.html` and `index.html` are the same file on a case-insensitive disk; a `:navbar` entry
naming `"Index"` links to it either way.

### Styling and search

ThirdMind's page markup is that of [goddinpotty](https://github.com/mtravers/goddinpotty), an
older static site generator for Logseq graphs, and it uses goddinpotty's stylesheet and search
scripts, which aren't yet bundled here. Get `default.css`, `hyper-roam.css`, `search.js`,
`elasticlunr.min.js` and `grid.js` from goddinpotty's
[`resources/public/`](https://github.com/mtravers/goddinpotty/tree/master/resources/public)
folder, put them in a folder of your own, and point `:static-dir` at it; the build copies that
folder's files into `assets/`. Pages always link `assets/default.css` and the two scripts;
`hyper-roam.css` is optional styling, linked only if you add `"assets/hyper-roam.css"` to
`:site-css`. Without them, pages render with Bootstrap's defaults: readable,
but the navigation bar is hard to see, the search box does nothing and the Index table is
empty. The search index itself (`assets/index.js`) and the maps are built either way.

Add your own stylesheets the same way: put the file in `:static-dir` and list it in `:site-css`
as `"assets/<name>.css"`.

## Configuration

A config is an EDN file. All paths are absolute or relative to the directory you run `lein`
in.

| Key | Default | Meaning |
|---|---|---|
| `:graph` | required | The graph folder (holds `pages/` and `journals/`) |
| `:db` | `<realm>.db` | The database file. A second file, `<db>.ids.db`, sits beside it |
| `:realm` | graph folder name | Name used inside the database and in page URIs |
| `:port` | 8080 | Port for `lein serve` |
| `:write?` | false | Let `lein watch` edit the graph: smart URLs and `#AskClaude` |
| `:site` | | Static site settings, below |
| `:blygger` | | Blyg settings, below |

`:site`:

| Key | Default | Meaning |
|---|---|---|
| `:main-page` | required | Front page; first in the navbar |
| `:build-dir` | `:output-dir`, else `target/site` | Where `lein site build` writes (`--out dir` overrides) |
| `:output-dir` | | The live site's directory, if you build somewhere else first |
| `:base-url` | | The site's public URL, for `og:url` tags |
| `:exit-tags` | `["Private"]` | Tags that keep a block, its children, or a page off the site |
| `:entry-tags` | `["EntryPoint"]` | Pages tagged with one of these are published |
| `:entry-pages` | | More pages to publish and to put in the navbar |
| `:entry-block-tags` | `["blyg" "blyg-pin" "blyg-reply" "blyg-response"]` | Blocks with these tags start the walk |
| `:page-suffix` | none | Suffix for page files, eg `".html"` |
| `:navbar` | | `[[label page-name] ...]` links in the navbar |
| `:short-title` | | Prefix for each page's `<title>` |
| `:description` | page title | `og:description` |
| `:colophon` | | Footer contents, as [hiccup](https://github.com/weavejester/hiccup) |
| `:site-css` | | Extra stylesheet URLs, linked after the built-in ones |
| `:static-dir` | | Files to copy into `assets/` (see [Styling and search](#styling-and-search)) |
| `:google-analytics` | | A Google Analytics id |
| `:blyg?` | true when `:blygger` is set | Show a "Blyg" link (to `blyg/`) in the navbar |
| `:goddinpotty` | | A goddinpotty config to take `:exit-tags`, `:main-page`, `:base-url` and `:output-dir` from. Only for migrating from goddinpotty |

`config/ammdi.edn` is the config for the graph ThirdMind was built for, with every section
filled in.

## Other commands

```bash
lein watch                  # keep the db in sync with the graph; Ctrl-C to stop
lein serve                  # browse the graph at http://localhost:<port>
lein search heidegger       # full-text search over the config's db
lein run <graph-dir> [db]   # rebuild a db from scratch and print a report
```

`watch` and `serve` take the config as `lein site` does, or a graph folder and db path
instead. `search` takes an optional db path.

### `lein watch`

Polls `pages/` and `journals/` every 2 seconds and re-reads each changed file. It waits until
a file has been still for one interval, since editors save while you type. On start it
catches up on whatever changed while it was stopped. A typical file syncs in well under a
second.

With `:write? true` (or `--write`) it also edits the graph:

- **Smart URLs.** A bare URL you've just typed becomes `[page title](url)`.
- **`#AskClaude`.** End a block with `#AskClaude` and ThirdMind sends that block's outline to
  Claude and nests the answer beneath it; `#AskClaudePage` sends the whole page. It needs
  `ANTHROPIC_API_KEY` in the environment, or falls back to the key in the logseq-claude
  plugin's settings (`~/.logseq/settings/logseq-claude.json`). `THIRDMIND_CLAUDE_MODEL` picks the model.

Writes wait until the file has been quiet for a few seconds, find their block again by
content, and replace the file atomically, so an edit you make in the meantime isn't lost.
Keep the graph under version control anyway.

`lein site build` syncs the database itself, so it doesn't need `lein watch` running.

### Blyg

`lein blyg publish` turns `#blyg` blocks into a blyg (protocol 0.3): JSON entries, archive and
permalink pages and a feed, in a directory of a site repository. `lein blyg push` commits and
pushes only that directory, and `lein blyg mention` sends webmentions for replies. With
`:auto-publish? true`, `lein watch` publishes and pushes once `#blyg` edits have been still for
`:quiet-ms`. The `:blygger` section of `config/ammdi.edn` shows the settings, and
[design/blygger.md](design/blygger.md) describes the format.

`lein blyg` needs a goddinpotty config file (`:blygger :goddinpotty`), from which it reads the
site URL and exit tags, and a goddinpotty-built site, from which it learns which pages are
published. Until that changes it is only usable alongside goddinpotty, so its settings aren't
documented here.

## Limits

- Only `.md` files directly inside `pages/` and `journals/` are read: no subfolders, no
  `.org`.
- Two files whose pages have the same name overwrite each other's blocks.
- Changing an `alias::` doesn't re-resolve links in other pages until those files change.
- A block reference to a block in a file that hasn't been synced yet is dropped.
- The watcher doesn't rebuild the site; run `lein site build` for that.

## How it works

### Data model

Pages, blocks and files are Tunamelt objects; relations are attributes holding URIs.

| type | uri | attributes |
|---|---|---|
| page | `hyperphor://<realm>/page/<encoded lowercase name>` | `:name :file :journal :journal-day :blocks :tags :alias :prop/*`; `:stub true` for pages that are only referenced |
| block | `hyperphor://<realm>/block/<id:: or page~outline.path>` | `:content :page :parent :order :depth :refs :tags :block-refs :marker :prop/*` |
| file | `hyperphor://<realm>/file/<encoded rel path>` | `:path :mtime :size :page`, as the watcher last read them |

```clojure
(t/find "block" {:attrs {:tags page-uri}})          ; blocks tagged #foo
(t/find "block" {:attrs {:marker "TODO"}})
(t/referrers page-uri {:attr :refs})               ; backlinks
(core/outline page-uri)                            ; outline rebuilt from :parent/:order
```

Blocks without an `id::` get ids from their position. A separate store (`<db>.ids.db`) gives
each block a stable ThirdMind id, matched by content when a file changes, which the blyg uses
to recognise an edited post.

### Tag handlers

Tag handlers (`watch/default-handlers`, tag name → fn of the block) run when a block newly
carries a tag: a tagged block counts as new unless a tagged block on that page had the same
uri or content before the change. Inserting blocks above it, which renumbers position-based
ids, doesn't fire it again. Editing a tagged block's text does. Handlers don't run during a
full rebuild.

### Parsing

- Legacy file names: `%XX` is decoded and `title::` overrides the file name. Journals
  (`yyyy_MM_dd.md`) are named as Logseq names them by default, `Jul 5th, 2013`.
- Page links are case-insensitive and resolve through `alias::`.
- Links inside fenced or inline code are ignored; `- ` lines inside a code fence are content,
  not blocks.

## Development

```bash
lein test
```

Tests use the fixture graph in `test/resources/graph` and temporary copies of it, never a
real graph. To work on Tunamelt at the same time, clone it beside this repository and link
it as a Leiningen checkout:

```bash
mkdir -p checkouts && ln -s ../../tunamelt checkouts/tunamelt
```

`design/` holds design notes and plans; `design/knowos-via-logseq.md` is the original spec and
`design/static-site.md` the site generator's.

## License

Apache 2.0; see [LICENSE.md](LICENSE.md).
