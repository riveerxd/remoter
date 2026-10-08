# Notes

Stuff about `claude` and the desktop that the code relies on. None of it is
documented anywhere, it all came from poking at it, so expect it to break on
some update. Versions are what I saw it on.

## Starting a Remote Control session

- `claude --remote-control=<name> --permission-mode bypassPermissions` with
  `--debug-file <path>`. The debug log is far more reliable than screen text:

  ```
  [bridge:init] bridgeId=... dir=/path/to/folder      claude is up
  [bridge:init] Registered, server environmentId=...  remote control registered
  [bridge:init] Created initial session session_...   ready
  ```

  About 1.5 s from start to ready, but I've seen 30 s once when it was slow
  loading CA certs. The ready timeout is 90 s for that reason.
- It writes `bridge-transcript-*.jsonl` and `debug-cse_*.log` next to the debug
  file, so the debug file has to live in a private per session dir.
- Tokens show as `[REDACTED]` in that log, so tailing it is fine.

## Folder trust

- `claude remote-control` never shows a prompt in an untrusted folder. It
  prints `Error: Workspace not trusted.` and exits 1 before any debug line.
- Interactive claude, which remoter runs, does show one. 2.1.295 drew "Accessing workspace: ...
  Quick safety check" with `❯ No, exit` and `Yes, I trust this folder` and
  waited, in a folder with no entry of its own under a trusted `~/Projects`.
  The same setup didn't ask again later, so what triggers it is unclear.
- Trust is inherited from the nearest ancestor that has an entry in
  `~/.claude.json`. An explicit `hasTrustDialogAccepted: false` on a child wins
  over a trusted parent. claude writes that `false` itself for folders it ran
  in with `--dangerously-skip-permissions`.
- So remoter doesn't rely on inheritance. Before every start and resume the
  agent sets `hasTrustDialogAccepted: true` on the exact folder. Folders on the
  deny list and home itself are refused before that, so they never get an
  entry.
- Worktree starts pass `--worktree=<session id>`. claude puts it at
  `<repo>/.claude/worktrees/<name>`, so that path is trusted up front too.
  Without its own entry, `--worktree` in a folder trusted only through a parent
  prints `Workspace trust not yet accepted` (2.1.286).
- Running claude processes rewrite `~/.claude.json` from memory, so the agent
  reads the entry back after writing it and retries if it got lost.
- If the dialog shows anyway, the agent sees those lines on screen, stops that
  claude and reports the session Stuck as untrusted within a few seconds.
  Retry from the phone starts a fresh one.

## Resuming

- Conversations live in `~/.claude/projects/<cwd with every non alnum char as
  "-">/<uuid>.jsonl`. The name is lossy (`a b` and `a-b` collide), so the
  first record's `cwd` is what actually says which folder it belongs to.
- Titles and the last prompt get re-emitted often, so the tail of the file has
  the current ones. Files hit 30 MB, only head and tail are read.
- `claude remote-control --session-id` only takes cloud ids. A local
  conversation resumes with `--resume <uuid>` on the normal argv.
- A conversation that was on Remote Control before reattaches instead:
  `[remote-bridge] Reattaching to session cse_...` and never prints
  `Created session`. That line counts as ready too.
- claude doesn't refuse a second resume of an open conversation. Both
  processes end up writing the same jsonl. remoter checks
  `~/.claude/sessions/<pid>.json` (pid alive and `procStart` matching
  `/proc/<pid>/stat`) and refuses with `conversation_open`.

## kitty and Hyprland

- kitty expands `$VAR` in the child's argv. Nothing user controlled goes on
  kitty's command line, only the session id and a path to a 0600 spec file
  that remoter-exec reads.
- `--hold` leaves an interactive shell behind. remoter-exec keeps the window
  open itself instead.
- The window rule for workspace 9 is set at runtime with `hyprctl keyword`
  before every spawn, since a Hyprland reload drops it.
- The user manager already has `WAYLAND_DISPLAY` and
  `HYPRLAND_INSTANCE_SIGNATURE`, so the agent as a user service can reach both.
- Sessions die when Hyprland exits or you log out. That's accepted.

## NoNewPrivileges

remoter-agent.service has `NoNewPrivileges=yes`, and sessions inherit it, so
`sudo` fails inside a phone started session. That's not a wall though:
`systemd-run --user --pipe --wait ...` asks the user manager to run something
and that process starts without the flag. Don't put passwordless sudo rules
on this box and think phone sessions can't use them.

## WireGuard on the phone

The WireGuard app only takes a PSK by typing it or importing a full config.
`phone-tunnel.sh` makes the phone's key on the laptop in a tmpfs dir, shows the
whole config as a QR once and shreds it. The key is in laptop RAM for a few
seconds, which I'm fine with: whoever owns the laptop at that moment owns the
tunnel's far end anyway.

## Tests

The headless session tests each start a scope and wait up to 3 s for it. Run
all at once on a loaded box and systemd falls behind, then unrelated tests
fail with "scope never appeared". `--test-threads=4` fixes it.
