# AI Doc Assist

Cited answers from SIP documents: a Java / Spring Boot re-implementation of SKB. The design is in
[CLAUDE.md](CLAUDE.md), and what was built, phase by phase, is in
[IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md). If you are installing the delivered bundle
rather than developing, read [packaging/README.md](packaging/README.md) instead.

## Requirements (development)

- **JDK 21.** Maven comes with the project (`mvnw`), so no separate install is needed.
- **Ollama** reachable over the network, with `qwen3-embedding:8b` and `frob/qwen3.5-instruct:4b`.

If your default `java` is another version, point `JAVA_HOME` at JDK 21 for the session:

```powershell
$env:JAVA_HOME = 'C:\DevTools\jdk\openjdk-21.0.2_windows-x64'
```

## Settings

Create a `.env` in the project folder. It is git-ignored; never commit it.

```
OLLAMA_URL=http://192.168.1.109:11434
ADMIN_USERNAME=admin
ADMIN_PASSWORD=at-least-10-chars
```

Settings take effect in this order, highest first: environment variables, then `.env`, then
`application.properties`. Other keys: `EMBED_MODEL`, `CHAT_MODEL`, `DB_PATH` (default
`data/aidocassist.db`), `UPLOAD_DIR` (default `uploads`), `CHAT_EXAMPLES` (example questions,
separated by `|`), `SYNC_SCHEDULER=false` (turns the scheduled syncs off).

## How to run

Build and start the web app:

```powershell
.\mvnw.cmd package                      # build + all tests -> target\aidocassist.jar
java -jar target\aidocassist.jar        # http://localhost:8080/docassist
```

Use `.\mvnw.cmd package -DskipTests` for a quicker build. On first start the database is created
and migrated, and the admin from `.env` is created if no admin exists yet.

The same jar also runs command-line tools. Run them from the project folder so they use the same
`.env` and database:

| Command | What it does |
|---|---|
| `java -jar target\aidocassist.jar seed init-data` | Load groups, users, user groups and filesystem entries from SKB's CSV format. |
| `java -jar target\aidocassist.jar sync` | List the sync entries with their ids. |
| `java -jar target\aidocassist.jar sync <id>\|all [--reread]` | Run one entry, or every enabled one. `--reread` ignores file dates. |
| `java -jar target\aidocassist.jar chunks <file>...` | Show how a file is parsed and chunked. No database, no Ollama. |
| `java -jar target\aidocassist.jar search "question" [--groups a,b]` | The fused evidence list (retrieval only, no LLM). |
| `java -jar target\aidocassist.jar ask "question" ["follow-up" ...] [--groups a,b]` | Full answers. Several questions run as one conversation, so follow-ups get rewritten. |

Without `--groups`, `search` and `ask` see every group (an admin's view).

Tests:

```powershell
.\mvnw.cmd test                          # all tests
.\mvnw.cmd test -Dtest=ChunkingTest      # one class
```

`OllamaClientTest` calls the real Ollama and runs only when `OLLAMA_URL` is set in the environment.

## How to repackage (the bundle)

The bundle is a folder with the app and its own Java runtime, zipped. The target machine needs no
Java.

```powershell
$env:JAVA_HOME = 'C:\DevTools\jdk\openjdk-21.0.2_windows-x64'
powershell -ExecutionPolicy Bypass -File packaging\bundle.ps1             # with tests
powershell -ExecutionPolicy Bypass -File packaging\bundle.ps1 -SkipTests  # quicker
```

Output:

- `target\bundle\AIDocAssist\`: ready to run with `start.bat`.
- `target\bundle\AIDocAssist-<version>-windows.zip`: the file to hand over.

What goes into it:

| From | Into the bundle |
|---|---|
| `target\aidocassist.jar` | `app\aidocassist.jar` |
| the JDK in `JAVA_HOME` | `runtime\` (via jpackage) |
| `packaging\start.bat` | starts the app from its own folder |
| `packaging\application.properties` | the installer's config |
| `packaging\README.md` | the installer's guide |

For a new release, raise `<version>` in `pom.xml`, which becomes the zip name and the exe's
version, then run the script. To change what installers see, edit the files in `packaging\`;
they are copied in on every build.

jpackage builds only for the OS it runs on. This script makes a Windows bundle. For Linux, run it
with `pwsh` on a Linux machine with JDK 21; it would also need a `start.sh`, which isn't written
yet.

## Tips and tricks

- **Try a change without touching your real data:** set `DB_PATH` and `UPLOAD_DIR` to a scratch
  location for that session, e.g. `$env:DB_PATH='C:\temp\try.db'`. A fresh database is created
  and migrated on start.
- **Start over:** stop the app and delete `data\` (and `uploads\` if you used uploads). The next
  start recreates the schema and the admin. Fresh databases have **no groups**: add them under
  Groups, or run `seed init-data`.
- **The admin sees nothing in the chat?** Admin rights don't include document access. Give the
  admin a group under Users.
- **Check retrieval before blaming the model:** the admin **Retrieval** tab (`/admin/retrieval`)
  or the `search` command shows exactly which evidence the model would get. If the right chunk
  isn't there, the problem is parsing, chunking or retrieval, not generation.
- **Check how a file is chunked:** `chunks <file>` shows the sections and titles without syncing.
  This is the quickest way to inspect a difficult PDF table.
- **Re-index after changing a parser or chunker:** dates haven't changed, so a normal sync skips
  every file. Run `sync all --reread` or click "Re-read all".
- **Don't let scheduled syncs interfere while debugging:** set `SYNC_SCHEDULER=false`.
- **One writer at a time:** stop the web app before running `sync` from the command line, so the
  two don't queue for the database lock.
- **Port 8080 already in use:** run with `--server.port=8081` after the jar name, or set
  `server.port` in `application.properties`.
- **Ollama unreachable:** the chat shows a clear message and saves nothing, and a sync ends as
  `failed` in its history. Check with `curl http://<ollama>:11434/api/tags`.
- **Slow first answer:** Ollama loads the model on the first call (several seconds). Later calls
  are 2–6 s.
- **Zip step fails with "file is being used by another process":** the script already uses
  `tar` for this. If it still fails, close the IDE's view of `target\bundle` and run the script
  again.
- **Back up** `data\` and `uploads\` together. Uploaded files exist only there.
