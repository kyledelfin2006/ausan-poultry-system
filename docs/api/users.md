# Users (OWNER only)

Every endpoint here answers `403` (empty body) for an assistant.

`UserView`, returned by all of them (never includes the password):

```json
{ "id": 2, "username": "maria", "displayName": "Maria Santos", "role": "ASSISTANT", "active": true }
```

## `POST /api/users`

| Field | Type | Required | Limits |
|---|---|---|---|
| username | string | yes | non-blank, max 50, unique |
| password | string | yes | 10-72 characters |
| displayName | string | yes | non-blank, max 100 |
| role | `OWNER` \| `ASSISTANT` | yes | |

`201` returns the `UserView`. Errors: `400`, `401`, `403`, `409` (`Username already taken`), `422` (a
[password rule](auth-session.md#password-rules)). User changes are recorded in the [audit trail](audit.md).

## `GET /api/users`

`200`: a plain array of every user (active and inactive), oldest first.

## `PATCH /api/users/{id}`

Any subset of:

| Field | Type | Limits |
|---|---|---|
| displayName | string | max 100, must not be blank |
| role | `OWNER` \| `ASSISTANT` | |
| active | boolean | `false` deactivates: the user's tokens stop working at once |

`200` returns the `UserView`. Errors: `400`, `403`, `404`, `422` for any of:

- blank display name;
- you cannot deactivate your own account;
- the last active owner cannot be deactivated or demoted (the system always keeps at least one).

## `POST /api/users/{id}/password`

Resets another user's password without the old one.

| Field | Type | Required | Limits |
|---|---|---|---|
| newPassword | string | yes | 10-72 characters |

`204` with no body. The target user's sessions end at once (they must log in with the new password); your own session is
unaffected. Errors: `400`, `403`, `404`, `422` (a [password rule](auth-session.md#password-rules)).
