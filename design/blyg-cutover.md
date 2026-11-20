# Blyg cut-over: goddinpotty → ThirdMind

One sitting, in order. Nothing here has been run yet.

Commands below use these names:

```bash
TM=/opt/mt/repos/hyperphor/thirdmind; GP=/opt/mt/repos/goddinpotty
CFG=$GP/resources/ammdi-config.edn; LIVE=/opt/mt/repos/hyperphor-git/ammdi/blyg
STATE=/opt/mt/repos/ammdi/.thirdmind-blyg-state.edn; IDS=/opt/mt/repos/ammdi/.thirdmind-ids.edn
STAGE=/tmp/blyg-stage
```

## Decide first

- **Where ThirdMind's blyg state lives.** Decided: in ammdi, next to `.blyg-state.edn`, along
  with a text export of the ids (`--ids-backup`, one line per graph file, so it diffs small).
  Previously: It is irreplaceable, like `.blyg-state.edn`, and needs
  to be git-tracked and backed up. The same goes for `ammdi.ids.db`, the block ids it is keyed by.
  Options: a small private repo, or next to `.blyg-state.edn` in ammdi. ThirdMind's own rule is
  not to write into the graph repo, so that would be an exception.
- **The 11 items whose `content_md` goddinpotty left empty.** These are mostly headings; their
  HTML is fine. By default they stay frozen until edited. The alternative is to republish them
  now, which adds about 11 update events to the feed.
- **Syndication.** Posting to Mastodon/Bluesky isn't ported yet (phase 4). goddinpotty's
  `-main-blyg-syndicate` reads its own `.blyg-state.edn`, so after cut-over it finds nothing new
  and posts nothing: harmless, but new items aren't posted until syndication is ported.
- **Replies.** A new `#blyg-reply` needs the remote item fetched, which isn't ported yet. One is
  pending: Nov 12th, 2026, replying to `blyg.pioneeringspirit.xyz`. Existing replies are carried
  over with their stored snapshots.

- **Unpushed local build.** `hyperphor-git/ammdi/blyg` has 26 uncommitted changes from a
  goddinpotty build around Nov 16; the last push was Nov 15. Push or discard them before step 4,
  so the diff is against what is actually deployed.
- **No automation to race with.** `bin/publish-ammdi.sh` has no launchd job installed, and the
  "ammdi generated" commits are manual. Just don't run a goddinpotty build while a ThirdMind
  publish is writing: a build copies `blyg/` and then swaps the whole dir, so a publish landing
  in between would be lost.

## 1. Mint the real block ids

Your current `ammdi.db` predates ids, so most of its blocks have no `:tmid`, and the publisher
refuses to run without them. Stop `lein watch` and `lein serve`, then:

```bash
cd $TM && lein run /opt/mt/repos/ammdi    # rebuilds ammdi.db and creates ammdi.ids.db
```

Back up `ammdi.ids.db`, then restart the watcher.

## 2. Turn off goddinpotty's blygger, keeping `/blyg/`

A full build writes the whole site to a temp dir and replaces `hyperphor-git/ammdi` with it, so
once goddinpotty's blygger is off, its next run would delete `/blyg/`. Patch `output-bm` in
`src/goddinpotty/core.clj`, just before `(replace-directory ...)`:

```clojure
    ;; Directories written by other tools (eg ThirdMind's blyg), carried across the swap. A page
    ;; file of the same name (the #blyg tag's page) gives way, as blygger/ensure-mount-dir! did.
    (doseq [d (config/config :preserve-dirs)]
      (let [old (fs/file (config/config :output-dir) d)
            new (fs/file output-dir d)]
        (when (fs/directory? old)
          (when (fs/exists? new) (if (fs/directory? new) (fs/delete-dir new) (fs/delete new)))
          (fs/copy-dir old new))))
```

Then in `ammdi-config.edn`, set `:blygger {:enabled? false ...}` and add `:preserve-dirs ["blyg"]`.
goddinpotty's daily run builds from its working tree, which is currently `feature/blyg-pins` with
uncommitted changes, so make this edit in that tree, or merge it in first.

Run one build by hand (`lein run resources/ammdi-config.edn` in `$GP`, without pushing). Then
check that `/blyg/` survived:

```bash
git -C /opt/mt/repos/hyperphor-git status --short ammdi/blyg   # expect nothing
```

## 3. Final import

Run this right after goddinpotty's last blyg-enabled run, so the import sees final state:

```bash
cd $TM && lein blyg import /opt/mt/repos/ammdi --goddinpotty $CFG \
    --from /opt/mt/repos/ammdi/.blyg-state.edn --out $STATE
```

Check the report: unmatched entries should be only untagged or empty items, as in the dry run
below.

## 4. Stage, compare, go live

```bash
cp $STATE $STAGE.state.edn
lein blyg publish /opt/mt/repos/ammdi --goddinpotty $CFG --state $STAGE.state.edn --live $LIVE --out $STAGE
lein blyg diff $STAGE $LIVE      # expect 0 missing; the new and bumped items are all explainable
```

When that looks right, publish into the live dir and push:

```bash
lein blyg publish /opt/mt/repos/ammdi --goddinpotty $CFG --state $STATE --live $LIVE --out $LIVE --ids-backup $IDS
lein blyg push /opt/mt/repos/hyperphor-git --path ammdi/blyg --remote website
```

`publish` only ever adds or overwrites files. It never deletes, because items stay 200 forever.
It refuses to run if a new item looks like an unmatched old one (`--allow-new` overrides). Add
`--withdraw` only when you mean to withdraw untagged items.

## 5. Afterwards

- After the next goddinpotty build, check again that `/blyg/` is untouched.
- To publish an edit, run `publish` followed by `push` (step 4). The watcher hook that does this
  automatically is phase 2.

## Dry run, 2026-11-19 (scratch db; live site and state only read)

- Import: 329 of 416 entries matched to blocks. The other 87 are carried frozen: 63 are empty
  items, which goddinpotty published and ThirdMind doesn't; the rest are blocks no longer
  tagged. 0 withdrawn.
- Moved blocks: every publish also re-keys an item whose block lost its id (eg moved to another
  page) onto a new block with like text, when the pairing is unique both ways, as goddinpotty's
  `rekey-moved-blocks` does.
- Pages: the chrome outside the main region is byte-identical to live on 41 sampled pages. A
  pinned card and a reply card are identical to live, main region included.
- First publish: 5 new items (all absent from the live site; posts since the last run) and 3
  version bumps (two threads that gained a child, and one appended edit). Withdrawn 0, kind
  changes 0, thread-child flips 0, title changes 0, possible duplicates 0.
- Second and third publishes: 0 changes.
- Staging vs live: 0 missing, 10 new files (5 items × json + page), 6 json/xml differing (the
  3 bumped items, blyg.json, items/index.json, feed.xml).

## Cut-over, 2026-11-19 (done, not pushed)

- Real ids minted: `ammdi.db` was rebuilt with `ammdi.ids.db` (123,411 blocks) and its times
  backfilled. The old db is kept as `ammdi-old.db`. The watcher, :2284 and :2285 were restarted
  on the new db, with logs in `target/*.log` (thirdmind and thirdmind-edit).
- goddinpotty: patch and config applied in its working tree, uncommitted. A local build with its
  blygger off left `/blyg/` byte-identical (848 files).
- Import: 331 of 416 matched, 85 carried frozen. State is in
  `ammdi/.thirdmind-blyg-state.edn`, ids are exported to `ammdi/.thirdmind-ids.edn`, and Logseq
  auto-committed both.
- Publish into the local `hyperphor-git/ammdi/blyg`: 7 new items (none on the live site
  before), 3 bumps (real edits), nothing else. Parity of state against the dir: 438/438 files.
- Not done: the push (`lein blyg push /opt/mt/repos/hyperphor-git --path ammdi/blyg --remote
  website`), which waits on sorting out the other unpushed site changes.
