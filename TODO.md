# TODO

## Next version

* **The Mali shader report** (Poco X7 Pro, 2026-09-30). A device whose own compiler refuses a shader
  the port serves freezes at the loading bar with that shader pending. The port's bytes are valid ES 3.0
  to a strict front end and to ANGLE (all 39 served fragment shaders), so the driver's message decides,
  and there is no Mali device here. The record now carries the file's path and the compiler's own
  message (FINDINGS §21); the next step is a run of that build on Mali — the message names the stage and
  the line, and the candidates it will settle are listed in §21.5.

Nothing else outstanding. The shader/GPU pass — GUI precision, the uniform *link* probe instead of the
reported count, the dither served on the render grid — the Reset-resolution button, the 30 fps switch
and the exit that keeps the task in recents all shipped in v0.7.0 (README "What works today",
FINDINGS §16-§20).
