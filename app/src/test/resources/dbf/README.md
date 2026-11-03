# DBF JSON v3 fixture

`public-hannover-v3.json` contains the first two unchanged rows captured from
`https://dbf.finalrewind.org/8000152.json?version=3` on 2026-10-10.
The HTTP Date was `Sat, 10 Oct 2026 17:26:55 GMT`. Other rows were omitted.
This original capture does not claim station identity for production: the app
requests `no_related=1` to exclude nearby stations.

Data provenance: Deutsche Bahn, via DBF / IRIS.
DBF JSON implementation: <https://github.com/derf/db-fakedisplay>,
`lib/DBInfoscreen/Controller/Stationboard.pm`, IRIS v3 branch. DBF program code
is AGPL-3.0-or-later; Routely implements its own parser and does not copy DBF code.

The schema has no station EVA, date, trip ID or provider prediction timestamp.
Planned times are HH:mm, and message timestamps are message creation times.
Synthetic test mutations cover unknown realtime, zero-time fallbacks, platform
changes, whole-stop cancellation, partial-event matching and ambiguous dates.
