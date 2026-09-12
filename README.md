![Logo.png](docs/Logo.png)

# jDeltaTransfer

Client and server for transferring successive versions of large nested archives by sending only
what actually changed.

The archives in question are ZIP files that contain further archives in **CAB**, **ZIP** and
**7z** format. Both sides already hold an older version. The server can also serve a complete
version. Every version has a SHA-256 that both sides compute and verify, and all payload travels
as self describing blocks whose size is dynamic but bounded by a server side argument.

---

## Why a naive delta does not work here

Running rsync-style block matching over the compressed bytes of two archive versions saves
almost nothing. Changing one byte inside a file that sits in a nested 7z rewrites that 7z's whole
LZMA stream, which rewrites the enclosing ZIP entry, which changes everything from that offset
onwards. The compressed representation has no locality.

jDeltaTransfer therefore works on the **decompressed** content:

```
archive-v07.zip                          <- ZIP container
├── manifest.txt                         <- deflated entry      -> blocks of the plain text
├── content/f0001234.bin                 <- stored entry        -> blocks of the raw bytes
├── nested/data-01.zip                   <- ZIP container
│   ├── f0002001.txt                     <- deflated entry      -> blocks of the plain text
│   └── ...
├── nested/libs-02.cab                   <- CAB container (MSZIP)
│   └── f0003005.dat                     -> blocks of the raw bytes
└── nested/payload-01.7z                 <- 7z container (LZMA2)
    └── f0004010.bin                     -> blocks of the raw bytes
```

Two versions that differ in one file inside `libs-02.cab` share every block except the handful
covering the edited region, even though their compressed bytes have nothing in common.

The result of this analysis is a **blueprint**: a recursive recipe that describes how to rebuild
the original archive, byte for byte, from a set of content addressed blocks.

### Bit exact rebuilds

Decompressing is easy; re-compressing to the *identical* bytes is not. The rule here is that a
container is only taken apart if it can provably be put back together:

| format | how exactness is guaranteed |
|--------|-----------------------------|
| ZIP    | all headers, data descriptors, the central directory and any padding are kept verbatim; for each deflated entry the decomposer **probes which compression level reproduces the original stream** (aborting a candidate at the first differing byte) and records it. Entries no level reproduces — including encrypted or exotically compressed ones — keep their original compressed bytes. |
| CAB    | CFHEADER, CFFOLDER, CFFILE and CFDATA headers are kept verbatim; MSZIP blocks are re-encoded with the probed level and the same preset-dictionary chaining, and every block is compared against the original during decomposition. |
| 7z     | headers cannot be reconstructed from first principles, so the codec does a real round trip: it rebuilds the container and compares the SHA-256 with the original. Only an exact match is accepted. |

Anything that fails becomes an **opaque blob** — still chunked, still transferable, just with less
delta benefit. Correctness is never traded away. The server additionally rebuilds every archive
once after ingest and compares the hash (`--verify`, on by default).

---

## Block transfer

Blocks are cut with **FastCDC** content defined chunking, so an insertion shifts only the blocks
around it instead of renumbering everything that follows. Block sizes are therefore dynamic:

```
--avg-block-size 64KiB    target average
--max-block-size 4MiB     hard upper bound, never exceeded by any block on the wire
```

`--max-block-size` clamps the chunker's maximum and is enforced again in the framing layer, so a
misconfigured peer cannot push an oversized frame through.

Wire format — used for delta blocks and for full downloads alike:

```
frame := 0x01 | sha256[32] | length:int32 | payload[length]
end   := 0x00 | frameCount:int64 | totalBytes:int64
```

The receiver verifies every block against its announced hash on arrival, and the trailer
distinguishes a complete transfer from a truncated one.

---

## HTTP API

| method | path | purpose |
|--------|------|---------|
| GET  | `/` | human readable version list |
| GET  | `/api/versions` | **all versions with their SHA-256 hashes** |
| GET  | `/api/versions/{id}` | one version |
| GET  | `/api/versions/{id}/blueprint` | rebuild recipe, gzipped |
| POST | `/api/versions/{id}/blocks` | requested blocks, framed (the delta) |
| GET  | `/api/versions/{id}/full` | complete archive, framed |
| GET  | `/api/config` | block size limits the client must honour |
| POST | `/api/rescan` | ingest archives dropped into the archive directory |
| GET  | `/health` | liveness |

`GET /api/versions` returns, for every version: id, file name, size, `sha256`, the blueprint's
own `sha256`, block counts and the ingest timestamp.

### API Documentation

**[docs/api.md](docs/api.md)** documents every field, the error behaviour (errors are plain text,
not JSON) and the binary formats — blueprint, hash list and block stream. Machine readable JSON
Schemas are in [docs/schema/](docs/schema/); `ApiContractTest` validates the server's actual
responses and the on disk `index.json` against them on every build, so the reference cannot drift
away from the implementation.

### Persistent hashes

The server keeps its state under `--store`:

```
store/
├── index.json          version list with all hashes (atomic replace on every change)
├── blueprints/*.bp     one gzipped blueprint per version
└── blocks/
    ├── data.pack       append-only block payloads
    └── index.bin       hash -> offset/length, replayed into memory on startup
```

`index.json` records the block parameters the store was built with. Restarting with different
limits is refused rather than silently destroying deduplication (`--force-reindex` to rebuild).

---

## Building

Requires a JDK 21 or newer (developed against JDK 24).

```bash
./gradlew fatJar
```

produces `build/libs/jDeltaTransfer-1.0.0-all.jar`. The wrappers `jdt.cmd` / `jdt.sh` call it.

```bash
./gradlew test
```

runs the suite, including a full server-plus-client round trip over a real socket.

---

## Usage

### 1. Generate the test versions

Ten versions, ascending in size, starting at 1 GiB:

```bash
jdt gen --out data/archives --count 10 --start-size 1GB --growth 0.15 --threads 6
```

Every version is an outer ZIP with plain files plus nested ZIP, CAB and 7z archives. Version
*n+1* is derived from version *n*: most files stay byte identical, a few get a new revision that
rewrites a small region of their content, a few are dropped, and new ones are appended until the
target size is reached — the way successive releases of a real product behave.

> This writes roughly **20 GiB**. Use `--start-size 64MB` for a quick look.

### 2. Start the server

```bash
jdt server --archives data/archives --store data/store --port 8080 --max-block-size 4MiB
```

On startup it ingests every new archive: decomposes it, stores the blocks, records the hashes.
Then:

```bash
curl http://localhost:8080/api/versions
```

### 3. Fetch with the client

Full download of the oldest version:

```bash
jdt client fetch --version archive-v01 --out local/archive-v01.zip
```

Delta upgrade to the next version, using the local copy as the base:

```bash
jdt client fetch --version archive-v02 --base local/archive-v01.zip --out local/archive-v02.zip
```

The client decomposes its local base with the same rules and parameters, asks the server only for
the blocks it is missing, rebuilds, and verifies the SHA-256 against the hash the server
published before the file is moved into place.

```
jdt client list                      versions and hashes
jdt client info --version ID         details of one version
jdt client config                    server block size limits
jdt client hash --file FILE          SHA-256 of a local file
```

---

## Options

### Server

```
--archives DIR        directory holding the archive versions   (default: STORE/archives)
--store DIR           persistent hashes, blueprints and blocks  (default: ./jdt-store)
--port N              HTTP port                                 (default: 8080)
--bind HOST           bind address                              (default: 0.0.0.0)
--max-block-size SZ   hard upper bound for any block            (default: 4MiB)
--avg-block-size SZ   target average block size                 (default: 64KiB)
--threads N           HTTP worker threads                       (default: cores)
--verify true|false   rebuild and compare after each ingest     (default: true)
--force-reindex       discard blocks and blueprints, ingest again
--no-scan             do not ingest on startup
```

### Client

```
--server URL   server base URL                     (default: http://localhost:8080)
--cache DIR    where the decomposed base is cached (default: ./jdt-client-cache)
               One sub directory per base version, named by its hash. Nothing prunes it, so
               delete the sub directories of versions you no longer upgrade from.
--version ID   version to fetch
--base FILE    older local version (omit for a full download)
--out FILE     destination
```

### Generator

```
--out DIR           output directory                    (default: ./archives)
--count N           number of versions                  (default: 10)
--start-size SZ     target size of version 1            (default: 1GB)
--growth F          relative growth per version         (default: 0.15)
--seed N            master seed                         (default: 20260912)
--change-rate F     fraction of files revised per step  (default: 0.03)
--remove-rate F     fraction of files dropped per step  (default: 0.01)
--threads N         versions generated in parallel      (default: min(cores, 4))
--deflate-level N   level for deflated entries          (default: 6)
```

Sizes accept `4MiB`, `64K`, `1GB` or a plain byte count.

---

## Source layout

```
com.bw.jdt.core            blueprint model, chunker, block store, decomposer, reassembler
com.bw.jdt.core.format     ZIP / CAB / 7z container codecs and the deflate level probe
com.bw.jdt.proto           block framing
com.bw.jdt.server          version store (persistent hashes) and HTTP API
com.bw.jdt.client          delta and full download
com.bw.jdt.tools           deterministic test archive generator, incl. a CAB writer

docs/api.md             API reference: JSON fields, errors, binary formats
docs/schema/*.json      JSON Schemas, checked against the server by ApiContractTest
```

---

## Measured on the generated chain

Ten versions, 1.0 GiB growing to 3.5 GiB, 20.2 GiB in total, block size average 64 KiB with a
4 MiB hard limit.

Ingest (`--ingest-threads 4`, `--verify true`): every version decomposed into exactly 10
containers — the outer ZIP, four nested ZIPs, three CABs and two 7z archives — with **zero**
opaque fallbacks, and every one passed the rebuild-and-compare check.

The shared block store holds **5.2 GiB for all 20.2 GiB of archives**, because the versions share
most of their decompressed content.

See `demo.ps1` for the script that produces the table below: it downloads the first version in
full and then walks the chain, using each rebuilt version as the base for the next.

| version | mode  | archive     | transferred | saved  | verified | seconds |
|---------|-------|-------------|-------------|--------|----------|---------|
| v01     | full  | 1014.97 MiB | 1014.97 MiB |   —    | OK       |   5.8   |
| v02     | delta |    1.15 GiB |  196.91 MiB | 83.25% | OK       |  57.1   |
| v03     | delta |    1.32 GiB |  264.14 MiB | 80.52% | OK       |  65.7   |
| v04     | delta |    1.51 GiB |  343.80 MiB | 77.76% | OK       |  94.3   |
| v05     | delta |    1.73 GiB |  331.29 MiB | 81.30% | OK       | 128.2   |
| v06     | delta |    1.99 GiB |  430.45 MiB | 78.87% | OK       | 153.6   |
| v07     | delta |    2.29 GiB |  473.79 MiB | 79.77% | OK       | 176.5   |
| v08     | delta |    2.64 GiB |  500.96 MiB | 81.44% | OK       | 196.1   |
| v09     | delta |    3.02 GiB |  654.58 MiB | 78.86% | OK       | 216.0   |
| v10     | delta |    3.48 GiB |  723.49 MiB | 79.68% | OK       | 239.1   |

After the initial full download, upgrading through the whole chain delivered **19.1 GiB of
archives in 3.8 GiB on the wire — 80% saved**. "verified" means the rebuilt file was hashed
independently and compared against the SHA-256 the server publishes; every step matched.

Two things are worth reading out of the table. First, a large part of each delta is not overhead
but genuine new content: version *n+1* is 15% larger than version *n* by construction, so for
v10 roughly 471 MiB of the 723 MiB transferred is data that simply did not exist before. Second,
the `seconds` column is dominated by decomposing the local base, not by the transfer — see
"Possible next step" below.



## Known limits

- Multi part cabinets (`PREV_CABINET` / `NEXT_CABINET`) and Quantum/LZX compressed folders are not
  taken apart; such a cabinet is transferred as opaque blocks.
- 7z containers above 512 MiB are left opaque, because the round trip verification would cost
  more than the delta saves.
- ZIP archives with prepended data (self extracting stubs) whose central directory offset does not
  match the real position fall back to opaque blocks.
- All of the above are correctness preserving fallbacks; they only reduce how small a delta gets.

### Possible next step

Before each delta the client decomposes its local base to learn which blocks it can derive from
it, and caches that index under `--cache` keyed by the base's hash. Walking a chain of versions
therefore re-indexes at every step. The client could instead seed the cache for version *n+1*
directly from the blocks it just used to rebuild version *n*. That trades roughly two gigabytes
of disk writes for a couple of minutes of CPU per step, so it is worth doing on slow machines and
not worth doing on fast ones — which is why it is not the default. Note that this only works after
a delta fetch: the blocks of a `/full` download are cut over the *compressed* archive stream and
live in a different block space than the blueprint's decompressed blocks.
