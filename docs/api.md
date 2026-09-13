# jDeltaTransfer API reference

Machine readable schemas live next to this file in [`schema/`](schema/) (JSON Schema
draft 2020-12). `ApiContractTest` checks the running server's responses against them, so the two
cannot drift apart silently.

| document | schema |
|----------|--------|
| `GET /api/versions` | [version-list.schema.json](schema/version-list.schema.json) → [version.schema.json](schema/version.schema.json) |
| `GET /api/versions/{id}` | [version.schema.json](schema/version.schema.json) |
| `GET /api/config` on the transfer port | [client-config.schema.json](schema/client-config.schema.json) |
| `GET /api/config` on the admin port | [config.schema.json](schema/config.schema.json) |
| `POST /api/rescan` | [rescan.schema.json](schema/rescan.schema.json) |
| `STORE/index.json` (on disk) | [store-index.schema.json](schema/store-index.schema.json) |

Everything else the server serves is binary, described under [Binary formats](#binary-formats).

---

## Two ports

The API is split so the administrative half can be firewalled off, and by default binds to
loopback so it is unreachable from elsewhere even without a firewall.

| | transfer port (`--port`, default 8080) | admin port (`--admin-port`, default 8081) |
|--|--|--|
| `GET /api/versions`, `/api/versions/{id}`, `/blueprint`, `/full` | yes | yes |
| `POST /api/versions/{id}/blocks` | yes | yes |
| `GET /health` | yes | yes |
| `GET /api/config` | yes, **minimal** | yes, **full** |
| `GET /` (overview page) | **no** — 404 | yes |
| `POST /api/rescan` | **no** — 404 | yes |

The transfer port does not refuse the administrative endpoints, it does not have them: a 404 there
is the absence of a route, not a permission check. `--no-admin` leaves the admin port unopened
altogether, in which case those two endpoints exist nowhere.

`/api/config` is the one path that answers **differently per port**: the transfer port returns only
what a client acts on, the admin port the operator's full view. Neither reports the admin port
itself — a client has no use for it, and advertising it would work against the reason for the split.

Both ports take any address the host has, so the admin half can live on a management interface
instead of loopback (`--admin-bind 10.0.99.5`). An address that cannot be resolved or does not
belong to the host fails at startup, naming which port it was; nothing falls back to the wildcard.

## TLS

Both ports speak HTTPS when the server is started with `--tls-keystore`, and plain HTTP otherwise;
there is no mixed mode. TLS 1.2 is the floor. Certificate and hostname verification are the JDK
defaults and cannot be switched off — for a privately issued certificate, point the client at a
truststore holding it.

TLS encrypts the channel and proves the client reached the intended server. It does **not** limit
who may ask: with no client certificate required, anyone who can reach the transfer port can
download every archive. `--tls-require-client-cert` together with `--tls-truststore` is what makes
the handshake act as access control.

## Conventions

- All JSON is UTF-8, `Content-Type: application/json; charset=utf-8`, pretty printed.
- All hashes are SHA-256 as 64 lower case hex characters. `hashAlgorithm` states this explicitly
  in every document rather than leaving it implicit.
- Byte counts are integers and may exceed 2^31, so parse them as 64 bit.
- `sizeHuman` is for humans. Never parse it; use `size`.
- **Errors are not JSON.** A failing request answers `text/plain; charset=utf-8` with a one line
  message: `404` for an unknown version or action, `405` for a wrong method, `500` otherwise. If
  your client branches on the response body, branch on the status code and the `Content-Type`
  first.

---

## GET /api/versions

The version list with all hashes — the endpoint the task description asks for.

```json
{
  "hashAlgorithm" : "SHA-256",
  "count" : 10,
  "versions" : [ {
    "id" : "archive-v01",
    "fileName" : "archive-v01.zip",
    "size" : 1064277349,
    "sizeHuman" : "1014.97 MiB",
    "hashAlgorithm" : "SHA-256",
    "sha256" : "02ba9ed47039473ae7dba1542116fb6a9b3d12ebe2704dc0c74d51c13d1a7c40",
    "blueprintSha256" : "8f23c05d568226eb467180039dd60e008e23ef071b84e46e2d5e45ad2864ef19",
    "blueprintBytes" : 709905,
    "blockRefs" : 19036,
    "distinctBlocks" : 19036,
    "containers" : 10,
    "opaqueContainers" : 0,
    "ingestedAt" : "2026-09-12T16:18:03.760208500Z"
  } ]
}
```

Entries are ordered by `id` ascending.

### Version fields

| field | type | meaning |
|-------|------|---------|
| `id` | string | Stable identifier, the archive file name without its extension. Goes into every `/api/versions/{id}/...` path. |
| `fileName` | string | File name inside the server's `--archives` directory. |
| `size` | int64 | Archive size in bytes. |
| `sizeHuman` | string | Rendering of `size` for display. Informational. |
| `hashAlgorithm` | string | Always `SHA-256`. |
| `sha256` | hex(64) | Hash of the complete archive. **This is what a client verifies its rebuilt file against.** |
| `blueprintSha256` | hex(64) | Hash of the gzipped blueprint. Lets a client cache a blueprint and notice a changed one. |
| `blueprintBytes` | int64 | Size of the gzipped blueprint — part of what a delta transfer costs. |
| `blockRefs` | int64 | Block references in the blueprint, counting repeats. |
| `distinctBlocks` | int64 | Distinct blocks needed. The gap to `blockRefs` is deduplication within this one version. |
| `containers` | int64 | Nested archives that were decomposed, including the outer archive. |
| `opaqueContainers` | int64 | Archives that could not be reproduced byte for byte and are carried as plain blocks instead. **`0` is the good case.** A higher value never threatens correctness, it only means this version's delta is bigger than it could be. |
| `ingestedAt` | date-time | When the server decomposed the archive (UTC, ISO-8601). |

---

## GET /api/versions/{id}

One version, exactly the object described above. `404 text/plain` if `id` is unknown.

---

## GET /api/config

### On the transfer port — minimal

Only what a client acts on. Schema: [client-config.schema.json](schema/client-config.schema.json).

```json
{
  "hashAlgorithm" : "SHA-256",
  "maxBlockSize" : 4194304
}
```

| field | type | meaning |
|-------|------|---------|
| `hashAlgorithm` | string | Always `SHA-256`. |
| `maxBlockSize` | int | The server's `--max-block-size`. **No frame on the wire exceeds it**; a client must reject a larger one. This is the only field the transfer code reads. |

Everything in the operator's document below is left out on purpose. The block size parameters and
the decomposition limit already travel inside each blueprint, and in the authoritative form —
per version, not per current server setting. The transport encoding is stated by the response
headers, per response, which is what a client must go by anyway. And the store statistics are
operational data a client has no reason to see.

### On the admin port — full

The operator's view. Schema: [config.schema.json](schema/config.schema.json).

```json
{
  "hashAlgorithm" : "SHA-256",
  "maxBlockSize" : 4194304,
  "blockSizeMin" : 16384,
  "blockSizeAvg" : 65536,
  "blockSizeMax" : 262144,
  "storedBlocks" : 74508,
  "storedBlockBytes" : 5491555168,
  "maxSevenZDecomposeSize" : 536870912,
  "transportCompression" : "gzip",
  "transportCompressionLevel" : 1
}
```

| field | type | meaning |
|-------|------|---------|
| `maxBlockSize` | int | The server's `--max-block-size`. **No frame on the wire exceeds it**; a client must reject a larger one. |
| `blockSizeMin` | int | Lower bound of the content defined chunker, except for the final block of a payload. |
| `blockSizeAvg` | int | Target average, a power of two. |
| `blockSizeMax` | int | Largest block the chunker actually emits, derived from `blockSizeAvg` and clamped by `maxBlockSize`. |
| `storedBlocks` | int64 | Distinct blocks across all versions. |
| `storedBlockBytes` | int64 | Payload bytes in the block store. Far below the sum of all archive sizes, because versions share blocks. |
| `maxSevenZDecomposeSize` | int64 | The server's `--max-7z-size`: largest nested 7z opened up rather than carried as opaque blocks. **Informational only** — see below. |
| `transportCompression` | string | `gzip` if the server will compress the block stream for a client that asks for it, else `none`. |
| `transportCompressionLevel` | int | The gzip level in use, or 0 when compression is off. |

`maxSevenZDecomposeSize` is reported for operators, not for clients to act on. The authoritative
value for a given version travels inside that version's blueprint, because the client must
decompose its local base under the same policy that produced the blueprint it is fetching. A
server whose configuration changed after ingest therefore still serves consistent deltas for the
versions it ingested earlier.

`blockSizeMax <= maxBlockSize` always holds. The two differ whenever the average leaves room
below the hard limit — with the defaults above, blocks stay at or below 256 KiB even though
4 MiB would be permitted.

---

## POST /api/rescan

**Admin port only.** Ingests archives that were dropped into the archive directory after startup.
Synchronous: the response arrives once decomposition has finished, which takes minutes for gigabyte
archives — which is precisely why it is not reachable from the transfer port.

```json
{ "ingested" : 2, "total" : 12 }
```

`405 text/plain` on `GET`.

---

## GET /health

`200 text/plain` with the body `ok`.

---

## GET /

**Admin port only.** An HTML page with the same version list, for looking at in a browser. The
transfer port answers `404 text/plain` here instead.

---

## Binary formats

These endpoints do not return JSON. They are documented here because a client needs all of them
to complete a transfer.

### GET /api/versions/{id}/blueprint

The rebuild recipe: a gzipped binary tree describing how to reassemble the archive from blocks.

- `Content-Type: application/x-jdt-blueprint`
- `X-JDT-Archive-SHA256` — hash of the archive this blueprint rebuilds
- `X-JDT-Blueprint-SHA256` — hash of the body, matching `blueprintSha256`

Payload layout (after gunzip), big endian, produced and parsed by `BlueprintCodec`:

```
magic      "JDTBP"            5 bytes
version    2                  uint8   (version 1 is still accepted on read)
archive    sha256             32 bytes
size       archive size       int64
chunkMin / chunkAvg / chunkMax               3 x int32
max7zSize  decompose limit    int64   (version 2 and later; defaults to 512 MiB for version 1)
root       node

node := 0x00 | size:int64 | n:int32 | n x (sha256[32], length:int32)      -- blob
      | 0x01 | format:uint8 | size:int64 | metaLen:int32 | meta
              | childCount:int32 | children                               -- container
format := 0 ZIP | 1 CAB | 2 7z
```

A *blob* is a plain byte range split into blocks. A *container* is an archive that was taken
apart; its `meta` is the codec private skeleton (headers, compression settings) and its children
are the decompressed payloads of its entries.

### POST /api/versions/{id}/blocks

Request the blocks that are missing locally. Body is a binary hash list:

```
magic   "JDTH1"   5 bytes
count             int32
count x sha256    32 bytes each
```

`Content-Type: application/x-jdt-hashes`. The response is a block stream (below). Asking for a
block the server does not have aborts the response mid stream, which the receiver detects through
the missing trailer.

### GET /api/versions/{id}/full

The complete archive as a block stream, cut by the same content defined chunker.

- `X-JDT-Archive-SHA256`, `X-JDT-Archive-Size`, `X-JDT-Max-Block-Size`
- Concatenating the frame payloads in order yields the archive.

### Block stream

Used by both `/blocks` and `/full`. `Content-Type: application/x-jdt-blocks`.

Send `Accept-Encoding: gzip` to get the stream compressed; the server answers with
`Content-Encoding: gzip` when it did so, and with no such header when it did not — either because
the request did not ask or because the server was started with `--compress false`. Check the
header, do not assume. The frame hashes are over the **uncompressed** payload, so decompress
first and then parse frames exactly as below. `/api/config` reports whether the server offers it
at all.

```
frame := 0x01 | sha256[32] | length:int32 | payload[length]
end   := 0x00 | frameCount:int64 | totalBytes:int64
```

Two properties matter here. Each frame carries its own hash, so a receiver verifies blocks as
they arrive rather than discovering corruption only at the end. And the trailer makes a complete
transfer distinguishable from a truncated one — without it, a connection dropped at a frame
boundary would look like a clean end of stream.

No `length` ever exceeds `maxBlockSize` from `/api/config`; a client should treat a larger value
as a protocol error.

---

## Persistent store format

`STORE/index.json` is the server's durable hash record, written with an atomic replace so a crash
cannot leave it half updated. Schema: [store-index.schema.json](schema/store-index.schema.json).

```json
{
  "formatVersion" : 1,
  "hashAlgorithm" : "SHA-256",
  "chunkMin" : 16384,
  "chunkAvg" : 65536,
  "chunkMax" : 262144,
  "versions" : [ { "id" : "archive-v01", "...": "as in the API, minus sizeHuman and hashAlgorithm",
                   "fileModified" : "2026-09-12T16:02:41.113Z" } ]
}
```

`fileModified` is the archive file's last modified date at ingest; it is not part of the API. A
scan ingests a file again when its size or this date differ from the index, so an archive
replaced by one of the same size is noticed. Entries from an older index lack the field; the next
scan records the current date for them without ingesting again.

`chunkMin` / `chunkAvg` / `chunkMax` are recorded because block boundaries depend on them.
Starting the server with different values is refused rather than accepted, because mixing block
parameters in one store would silently stop versions from sharing blocks. `--force-reindex`
rebuilds the store under new parameters.

The rest of the store:

```
STORE/
├── index.json          this document
├── blueprints/{id}.bp  one gzipped blueprint per version
└── blocks/
    ├── data.pack       append-only block payloads
    └── index.bin       32 byte hash + int64 offset + int32 length per record
```

`blocks/index.bin` is replayed into memory on startup. A torn trailing record from a crash is
detected and truncated, and `data.pack` is cut back to the last block the index vouches for.
