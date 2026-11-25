# Last.fm history API research

Checked against Last.fm's official API documentation on 2026-11-25.

- `user.getRecentTracks` accepts a username and API key and does **not** require a user session. It supports `page`, `limit` (maximum 200), and UTC Unix-second `from`/`to` bounds; the response includes paging totals and timestamped track records. A currently playing item may be returned with `nowplaying="true"` and must not be imported as a historical play. The documented error set includes transient service failures and rate-limit error 29. Source: [1].
- Last.fm asks API consumers to send an identifiable User-Agent and to avoid excessive call frequency; the intro page points to its API terms for applicable limits. This supports a conservative delay/backoff policy and explicit handling of HTTP/API error conditions, but the official pages reviewed do not publish a numeric rate quota for this read endpoint. Sources: [2] [3].
- The Last.fm authentication specification describes API key/secret/signature/session flows for methods that require authentication. Since `user.getRecentTracks` explicitly says authentication is not required, history import can use the configured API key and the user's Last.fm username without collecting their Last.fm password or retaining an account session. Source: [3].
- The endpoint exposes timestamped scrobble pages; its documentation does not state a fixed maximum historical age. Avoid promising that an account's entire lifetime is available until verified against the actual account/API response.

## References

[1]: https://www.last.fm/api/show/user.getRecentTracks "Last.fm user.getRecentTracks API method"
[2]: https://www.last.fm/api/intro "Last.fm API introduction"
[3]: https://www.last.fm/api/authspec "Last.fm API authentication specification"
