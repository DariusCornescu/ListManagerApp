# Team-Session Android UI — Design Spec

**Date:** 2026-06-10
**Status:** Approved by Darius
**Scope:** Android client only. Backend team endpoints already exist
(`app/routers/teams.py`, `team_id` on `/api/session/active` and
`/api/session/create`) and are not modified.

## Goal

Expose the backend's team system in the Android app: create/join teams from the
phone, and switch the whole app between a Personal workspace and team
workspaces, where each workspace has its own active shared session.

## Decisions made

- **Context model: workspace switcher.** A `Workspace` is `Personal` or
  `Team(id, name)`. The whole app (session screen, voice input, PDFs) operates
  in the selected workspace until switched. Selection persists across restarts.
- **Management scope: essentials.** Create team, generate & share invite code,
  join via code, view members, leave team, owner-only remove member. Role
  editing and team rename/delete stay API-only for now.
- Team management actions require connectivity (no offline queueing for them).
  Session *editing* keeps the existing offline queue behavior in all workspaces.

## Architecture

### WorkspaceManager (new)
Singleton (constructed alongside existing repositories), exposing:
- `currentWorkspace: StateFlow<Workspace>`
- `switchTo(workspace)` — persists to DataStore, triggers session re-resolution
- `teams: StateFlow<List<TeamDTO>>` — refreshed from `GET /api/teams`
Persisted in DataStore (same pattern as auth token storage).

### Data layer changes
- `SessionEntity` gains `teamId: Long?` (Room migration, schema version bump).
  Active-session queries become workspace-scoped:
  `WHERE isActive AND teamId IS ?` (null = personal).
- `ListManagerApi` additions: `POST/GET /api/teams`,
  `POST /api/teams/{id}/invites`, `POST /api/teams/invites/{code}/accept`,
  `GET /api/teams/{id}/members`, `DELETE /api/teams/{id}/members/{userId}`,
  and `team_id` query/body params on `getActiveSession` / `createSession`.
- New DTOs: `TeamDTO`, `TeamMemberDTO`, `TeamCreate`, `InviteDTO`.
- New `TeamRepository` wrapping the team endpoints (online-only).
- Sync layer (`SyncService`/`SyncWorker`/WebSocket resubscription): session
  resolution keyed by workspace so queued offline ops replay against the
  correct team session. Existing optimistic-locking (version/409) unchanged.

### UI
- **Drawer:** workspace selector at top — current workspace name + dropdown
  (Personal + teams + "Manage teams…"). Switching reloads the session screen
  into that workspace's active session (lazily created, as today).
- **TeamsScreen (new, drawer entry):** team list, "Create team" (name dialog),
  "Join with code" (code entry), tap team → detail.
- **TeamDetailScreen (new):** member list, "Invite" (generate code → Android
  share sheet), "Leave team", owner-only remove member.
- **Session screen header:** small label showing current workspace name so it
  is always obvious where voice input lands.
- After joining a team via code: offer to switch to that workspace.

## Error handling
- Invalid/expired invite code → clear inline message.
- Switching to (or operating in) a team the user was removed from → fall back
  to Personal workspace with a toast.
- Team management calls with no connectivity → explicit "requires connection"
  message, no queueing.

## Testing
- Unit: `WorkspaceManager` persistence/switching; workspace-scoped
  active-session resolution (null vs teamId); Room migration test.
- Backend team flows already covered by pytest (not modified).
- Manual: two devices — create team on phone, join via emulator with invite
  code, verify both edit the same team session live; verify offline edits in a
  team workspace replay correctly on reconnect.

## Out of scope
- Role management UI, team rename/delete.
- Long-voice parsing (separate spec).
- Any backend changes.
