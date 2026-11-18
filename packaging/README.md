# AI Doc Assist — installation

AI Doc Assist answers questions from SIP documents with cited answers. This folder is the whole
application, including its own Java runtime and database engine. Nothing needs to be installed
on this machine except network access to the model services below.

## Before you start

- **Outside model services** (document text is sent to them): a **DeepSeek** API key for answers,
  and one or two **Google AI Studio** API keys for embeddings. This machine needs HTTPS access to
  `api.deepseek.com` and `generativelanguage.googleapis.com`.
- **Ollama** running on SIP's server, reachable from this machine: the fallback for embeddings when
  both Google keys fail, and for answers when no DeepSeek key is set. Pull both models:

  ```
  ollama pull qwen3-embedding:8b
  ollama pull frob/qwen3.5-instruct:4b
  ```

  Check it from this machine by opening `http://<ollama server>:11434/api/tags` in a browser. It
  should list both models.
- Windows 10/11 or Windows Server, 64-bit. About 500 MB of free disk, plus space for the documents
  you upload.
- Port **8080** open for incoming connections if colleagues use it from their own PCs.

## Install

1. Unzip the zip file to a folder of your choice, for example `C:\AIDocAssist`. Use a folder
   that the account running the app can write to.
2. Edit `application.properties` in that folder:
   - `ollama.url`: your Ollama server, for example `http://192.168.1.109:11434`.
   - `deepseek.api-key`: the DeepSeek key. `gemini.api-keys`: the Google keys, comma-separated,
     tried in order.
   - `admin.username` and `admin.password`: the first administrator. The password must be at
     least 10 characters. It is used only on the first start, while no admin exists yet.
3. Double-click `start.bat`. A console window opens and shows the log. Wait for
   `Started AiDocAssistApplication`.
4. Open `http://localhost:8080/docassist`, or `http://<this machine>:8080/docassist` from another PC. Log in as the
   admin.
5. Change the admin password (the **password** link). Then delete the password from
   `application.properties`.

## First setup in the app

1. **Groups**: a new install has none. Add the sensitivity groups first: `general`, `finance`,
   `contracts`, `hr-cv`, or whatever subset you need.
2. **Users**: add the people who will use it, each with one or more groups. A person only ever
   sees answers from documents tagged with one of their groups. **Give yourself (the admin) a
   group too**: being an admin doesn't show you any documents in the chat.
3. **Sync, then Filesystem**: add a folder of documents, pick its tags (groups) and a schedule.
   Every file in the folder gets that folder's tags. A scheduled entry syncs for the first time
   within a minute of being saved. With the schedule set to Off, click **Sync** yourself.
   **Sync, then Upload**: upload single files instead.
4. Ask a question on the chat page.

Supported files: `.docx .pdf .xlsx .md .txt`. Other files, and any file that looks like it contains
a password or key, are skipped and counted in the sync history.

## Daily running

- **Stop:** close the console window, or press Ctrl+C in it.
- **Scheduled syncs** only run while the app is running.
- **Start automatically with Windows (optional):** in Task Scheduler, create a task that runs
  `start.bat` "At startup", under an account with access to the document folders.
- **Logs:** `logs\aidocassist.log`.

## Back up

Back up these together, ideally while the app is stopped:

- `data\` holds the database: users, threads and the document index.
- `uploads\` holds the uploaded files. The app is the only copy of these.
- `application.properties`

## Upgrade

1. Stop the app and back up as above.
2. Unzip the new version to a new folder.
3. Copy `data\`, `uploads\` and `application.properties` from the old folder into the new one.
4. Start the new one with `start.bat`. Database changes are applied automatically on start.

## Command line

Run these from this folder while the web app is stopped:

```
start.bat sync all              sync every entry once
start.bat sync all --reread     the same, ignoring file dates
```

## Troubleshooting

| Symptom | Cause |
|---|---|
| "The model server can't be reached" in the chat, or a sync "failed: … Connect timed out" | Check the DeepSeek key, the Google keys (the log shows "gemini embedding with key n of 2 failed" and why), `ollama.url`, and that this machine reaches all three. |
| No tags to pick when adding a sync entry | No groups exist yet. Add them under **Groups**. |
| No answers, even to an easy question | No documents synced, or the user has no matching group. Check Sync and the user's groups. |
| Window closes at once | Port 8080 is already in use, or the folder is read-only. See `logs\aidocassist.log`. |
| Cannot log in as admin | The admin is only created while no admin exists. Check `admin.username` and `admin.password`, and that the password has at least 10 characters. |
