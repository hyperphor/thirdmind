# Blyg replies (stubs): what ThirdMind handles

Status as of 2026-11-21. Spec: protocol 0.3, §10.6 (stubs) and §15 (webmentions); see also
`/opt/reposed/blygger-spec/docs/v0.3-plan.md` and goddinpotty's `design/blygger-explained.md`.

## The idea

You don't comment on someone else's blyg. You publish a response on your own blyg, and that
response is a **stub**: a thread that quotes the other item (a frozen copy, with its id, version
and origin) and declares in `stub_of` that it replies to that one target. The other blyg then
gets a **webmention**, a POST to its endpoint saying "this page responds to yours", and its
software fetches our item to check the claim.

"Stub" has two meanings in this codebase. Here it is a reply item on the blyg. In the graph
model it means a page that is referenced but has no file (`{:stub true}`). The two are unrelated.

## How you write one

```
- My answer, briefly [Their post — Other Blyg](https://other.example/f/x/) #blyg-reply
```

`#blyg-reply` also implies `#blyg`. `#blyg-response` is a synonym in ThirdMind (goddinpotty
doesn't know it). The block must contain exactly one http(s) URL, either bare or as a
`[text](url)` link. That URL is the target. The tag must be written with `#`: a bare
`blyg-reply` is plain text.

## What happens at publish (`publish/candidates`, `plan`)

| Block | Result |
|---|---|
| Reply tag, one URL, target is a blyg item | Published as a stub (below) |
| Reply tag, one URL, target isn't a blyg item (or is unreachable) | Skipped: "couldn't be fetched as a blyg item" |
| Reply tag, several URLs | Skipped: "reply with more than one URL" |
| Reply tag, no URL | Published as an ordinary item, as goddinpotty does |

A stub is published as follows:

- The target URL is removed from the published text (`content/own-text`), because the target is
  cited rather than quoted.
- It becomes a **thread** even if the block has no children. Its transclusions are the children
  (if any), then the remote item, rendered as a `blockquote.blyg-transclusion` with
  `data-blyg-origin`. This puts the quote after the reply text, which is goddinpotty's default
  (`:reply-quote-position :after`). ThirdMind has no setting for it.
- `stub_of` is built from the stored snapshot (`publish/stub-of-ref`): origin, id, version and a
  `cited` block with retrieved, source, author, excerpt and url. Once set, it is frozen across
  later versions (`wire/next-entry`). A withdrawal tombstone drops it.
- If the surface contains any stub, `protocol_version` is 2.
- In the feed, the title is "Re: <source>" and the description starts with "In reply to …". The
  permalink and archive pages also show "In reply to …" (`pages.clj`).
- `:mention-target` and `:mention-endpoint` are recorded on the entry.

## Fetching targets (`remote.clj`)

Before planning, `lein blyg publish` (and the watcher's auto-publish) fetches a snapshot for each
reply target not yet in the state's `:remotes` (`publish/fetch-remotes`, `remote/resolve-target`,
ported from goddinpotty's `blyg_remote`). The URL may be the item document itself or a permalink
page with a `<link rel="alternate" type="application/json">`. The origin is taken from where the
item document was fetched (§12.2), and the manifest (`blyg.json`) supplies the source title,
author and webmention endpoint. This also runs on a dry run, which only reads.

A snapshot is fetched once and kept: the quote is a copy of the version the reply first saw
(§10.2). A target that doesn't resolve is retried on the next publish.

## Webmentions (`remote/send-mentions`)

After a stub is published, its target gets a webmention: a form POST of `source` (our permalink,
`<origin>f/<id>/`) and `target` (the remote page URL). The endpoint is the one in the target
blyg's manifest, else found by W3C discovery on the target page (Link header, then a
`rel=webmention` link). A mention is sent only once our item document is live and serving the
stub's current version, because the receiver fetches it to check (§15.4). Until then it waits.

The result is recorded on the entry as `:mention-sent`. A stub isn't sent again once a mention is
accepted (2xx) or terminally refused (4xx other than 429) for the target's version. Because
`stub_of` is frozen, that version doesn't change, so in practice each stub is announced once.
Unreachable endpoints and 5xx responses are retried.

Two ways to run it:

- `lein blyg mention` sends whatever is pending and writes the state file.
- The watcher's auto-publish tries mentions after each publish. It retries every `:mention-ms`
  (default 2 minutes) while any stub is still waiting for the pushed site to go live, up to 10
  tries. Mentions still pending after that go out at the next publish or `lein blyg mention`.

## What isn't ported


- **Inbound stubs** (other blygs replying to ours) are not detected.
- **Non-blyg targets.** The spec allows `stub_of: {url}` for a plain web page. ThirdMind skips
  such replies. One imported remote (msweet.net) is such a page and is carried over as is.

## Where skips show up

`lein blyg publish [--dry-run]` prints `:skipped` in its report, and the watcher's auto-publish
logs a "blyg: skipped" line for each.

## In the graph now

- Nov 12th and Nov 21st, 2026: both replies now fetch and plan as stubs (dry run: 4 new items,
  the Nov 21st thread with its two children and the Nov 12th stub). The Nov 12th block has no
  text besides the URL, so its stub is only the quote.
- Nov 9th, 2026: `blyg-reply` written without `#`, so not a tag and not published.

## Tests

`publish_test/replying` covers the stub path: a `#blyg-response` stub against a stored
snapshot, a reply to an unfetchable target being skipped, and resolving a permalink page to its
item document (with a fake HTTP getter). `content_test` covers `reply-target` (one URL, link form, ambiguous) and a reply block's text.
`wire_test` covers a tombstone dropping `stub_of`.
