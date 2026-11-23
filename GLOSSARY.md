# Glossary

Terms used in ThirdMind's code and design docs, grouped by area. Spec: `design/knowos-via-logseq.md`.

## The system

- **ThirdMind**: a side process and knowledge store that runs alongside a Logseq graph. It
  mirrors the graph's markdown into a db and reacts to tags. It isn't tied to Logseq, and it
  writes to the graph only through `graph_write.clj`.
- **Graph**: a Logseq directory of `.md` files, split into `pages/` and `journals/`. The real one
  is AMMDI (`/opt/mt/repos/ammdi`).
- **AMMDI**: the main Logseq graph, published at `ammdi.hyperphor.com`.
- **Tunamelt**: the persistence library, a SQLite-backed object store (`t/get`, `t/find`,
  `t/referrers`, `t/search`). It lives in `checkouts/tunamelt`.
- **Realm**: a Tunamelt namespace, one per graph. It's derived from the graph directory's name
  (`core/realm-for`): `ammdi`.
- **Config**: per-graph settings in `config/ammdi.edn` (`:graph :db :port :write? :blygger`).
  Commands run with no arguments pick it up (`config.clj`).
- **goddinpotty**: the older static-site generator that published AMMDI and the blyg. ThirdMind
  replaces its blygger, and in time its publishing generally.

## Graph model

- **Page**: a Logseq page, stored as a `page` object. A page that's referenced but has no file is
  a **stub** (`{:stub true}`).
- **Page key**: the canonical form of a page name: trimmed, lower-cased and encoded
  (`ingest/page-key`). Every page URI goes through it.
- **URI**: an object's db identity, such as `hyperphor://<realm>/page/<key>`, `/block/<id>` or
  `/file/<rel>`. URIs are built only with `ingest/page-uri`, `block-uri` and `file-uri`.
- **Block**: one outline bullet. It has `:content` (raw Logseq markdown), `:page`, `:parent`,
  `:order`, `:refs`, `:tags`, and optionally `:tmid`, `:created` and `:updated`.
- **Block id (path id)**: `page~path`, such as `new~0` or `nov 12th, 2026~3.1`. It's positional,
  so it changes when a block moves. Compare **tmid**.
- **Refs / tags**: page references from `[[link]]`, `#tag` or `#[[multi word]]`. In the watcher,
  a `[[link]]` counts as a tag.
- **Alias**: a page's `alias::` property. Refs to an alias resolve to the canonical page.
- **Properties**: `key:: value` lines on a page or block, coerced to numbers or booleans where
  they look like them.
- **File record**: a `file` object, `{:path :mtime :size :page}`. The watcher compares these with
  what's on disk to find changed files.

## Block identity and times

- **tmid**: ThirdMind's own permanent block id, 12 base32 characters. It's kept in
  `<db>.ids.db`, which survives rebuilds of the main db, and never written into the markdown.
  The blyg keys its items on tmids.
- **Ids store**: `<db>.ids.db`, with one record per graph file: `{:file :seen :gone? :blocks
  [[tmid content path] ...]}`. A record is **orphaned** (`:gone`) when its file disappears, so a
  renamed file can claim its ids again.
- **Ids backup**: a text export of the ids store (`.thirdmind-ids.edn` in ammdi), one line per
  file, so it diffs small.
- **Block identity**: matching a page's old blocks to its new ones by content after an edit
  (`identity.clj`). `lein replay-ids` scores how well this works on git history.
- **Block times**: `:created` and `:updated` on blocks, taken from `git blame` of the graph
  (`times.clj`). `lein times` backfills them, and the watcher keeps synced files current.

## The watcher (`lein watch`)

- **Sync**: one pass of the watcher. It re-ingests files whose mtime or size changed, and removes
  pages whose files are gone. `ingest/update-file!` returns a **diff**, `{:page :old :new}`, made
  of block entries.
- **Settle (`:settle-ms`)**: a file modified this recently is left for a later pass, since Logseq
  saves while you type.
- **Handler**: a function run when a block *newly* carries a tag (`ingest/new-tags`). The default
  `"blyg"` handler only logs.
- **Action**: a detect/act pair over each file's diff that writes to the graph, such as smart
  URLs or #AskClaude. Actions run only with `:write?`, one at a time, on a worker thread. A key
  that's **in flight** isn't queued again.
- **Quiet (`:quiet-ms`)**: how long a file must be unchanged before ThirdMind writes to it
  (`graph_write.clj`, 8 s by default). After waiting, the write re-finds the block by content and
  refuses if the file changed meanwhile. The `:blygger :quiet-ms` setting is separate: how long
  #blyg edits must be still before auto-publish runs (2 min).
- **Smart URL**: a bare URL newly typed into a block becomes `[page title](url)`. If the site
  refuses the fetch (401/403/429), the title is made from the URL instead (`url-title`).
- **#AskClaude / #AskClaudePage**: a block ending in this tag is sent to Claude, with its outline
  or the whole page as context. The answer is nested under a **wrapper** block that starts with
  `#AskClaude`. Blocks inside an answer (`in-answer?`) are left alone by the other actions.
- **Auto-publish**: with `:blygger :auto-publish?`, any change to a #blyg block, or a block under
  one, marks the blyg **dirty**. Once those edits have been still for `:quiet-ms`, the watcher
  publishes and pushes. It also runs once at startup.

## The blyg (protocol 0.3)

- **Blyg**: AMMDI's microblog, served at `ammdi.hyperphor.com/blyg/`. It's written into
  `hyperphor-git/ammdi/blyg/` and pushed to the `website` remote.
- **Blygger**: the software that publishes a blyg: goddinpotty's originally, ThirdMind's now
  (`blyg/*.clj`). Its spec is in `blygger-spec/docs/`.
- **Control tags**: `#blyg` publishes a block, `#blyg-pin` pins it, and `#blyg-reply` (with one
  URL) makes it a reply to a remote item.
- **Item**: one published unit, with a permanent **blyg id** (26-character base32). Items stay
  200 forever, so they're never deleted.
- **Fragment**: an item made from a single block. A block that's tagged and has children becomes
  a **thread**. Its children are **promoted** to fragments of their own (**thread children**),
  and the thread lists them as **transclusions**.
- **Entry**: an item's record in the state, `{:blyg-id :kind :version :content-md :content-html
  :content-hash :changelog ...}`.
- **Version**: bumped only when something a reader sees changes, judged by the **content hash**.
  ThirdMind compares against its own rendering, the **`:tm-base`**, so a rendering difference
  from goddinpotty alone never makes a new version.
- **Frozen entry** (`:tm-frozen`): an imported entry that still holds goddinpotty's content. It
  stays that way until the block is actually edited.
- **Pin**: a version fixed at its own URL, which is never un-pinned.
- **Withdrawal / tombstone**: unpublishing an item. It gets a final empty version but keeps its
  URL. Withdrawal is opt-in (`--withdraw`); by default an untagged item is just left as it is.
- **Reply stub / remote**: a #blyg-reply item quotes a remote item, whose stored copy is the
  **snapshot**. The snapshot is fetched at publish, and the target gets a webmention once the stub is live (`remote.clj`). `#blyg-response` is a synonym for `#blyg-reply`. See `design/blyg-replies.md`.
- **Rekey**: moving an entry onto a new block when its old block lost its tmid (for example, the
  block moved to another page), if the pairing by similar text is unique in both directions.
- **Possible duplicate**: a new item whose text closely matches an untagged old one. Publishing
  refuses when there is one, unless given `--allow-new`.
- **State**: the publisher's record of every entry, keyed by tmid, in
  `ammdi/.thirdmind-blyg-state.edn`. It's irreplaceable, since ids, versions and changelogs can't
  be rebuilt from the site. goddinpotty's older state, keyed by Logseq uuid, is `.blyg-state.edn`.
- **Surface**: the files a blyg serves: `blyg.json` (the **manifest**), `items/index.json`,
  `items/<id>.json`, `feed.xml`, and the HTML **archive** and **permalink** pages
  (`f/<id>/index.html`). The HTML borrows the site's chrome from the live pages, used as
  templates.
- **Exit tags**: tags such as `#Private` and `#ExitPoint` that keep a block, or everything under
  it, out of what's published. **Published pages** are the pages present in the site output.
  Links go only to those, and links to any other page are left as plain text.
- **Attribution**: the "From [Page]" link on a top-level item.
- **Import / migration**: the one-time conversion of goddinpotty's state to ThirdMind's
  (`lein blyg import`). Matched entries gain `:legacy-uuid`, and unmatched ones are carried
  frozen under their uuid.
- **Parity**: checking that a state regenerates a live surface exactly (`lein blyg parity`).
- **Cut-over**: the switch from goddinpotty's blygger to ThirdMind's (`design/blyg-cutover.md`).
  It's done.
- **Syndication**: posting new items to Mastodon and Bluesky. It isn't ported from goddinpotty
  yet.

## Elsewhere

- **UI levels**: `design/ui.md`'s stages for ThirdMind's own display. Level 0 is browsing via a
  live server (`lein serve`). Level 1 is publishing.
- **Pagent**: an agent whose prompt, schedule, state and output all live in one editable page
  (`design/pagents.md`).
