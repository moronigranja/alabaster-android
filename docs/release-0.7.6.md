## v0.7.6

The shader the Mali phone in issue #4 still refused — and with it that phone's boot stall — now serves in the
one spelling its own compiler measured as accepted.

* **The post pass's ramp is named instead of constructed.** `analog-filter.frag` builds its colour ramp as an
  array *constructor* passed as an argument (`colorRamp(noise.r, vec3[5](…))`). On that driver an array
  temporary inherits no element precision in *any* spelling of its brackets, which the phone's own self-test
  showed case by case: `vec3[5](`, the unsized `vec3[](`, and a qualified parameter all came back
  `S0032: no default precision defined for variable 'vec3[5]'`, while naming the ramp and assigning it
  element by element compiles. The port serves that now — the game's own five values, in its order — and only
  to a device whose own compiler refused the game's declarations. That refusal was the whole of its boot
  stall (`pending 105 (shader=1 …)`, first pending `texturedpost/analog-filter`).
* **The spelling self-test now asks what still answers something**: each shader the port lifts, twice — the
  game's bytes and the port's — instead of fourteen candidate spellings the device has since settled.

Under **ANGLE** nothing changes on that device: the gate finds the game's own bytes compile there, and the
game boots and plays.

[issue #4](https://github.com/moronigranja/alabaster-android/issues/4) ·
[fuller notes](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.7.6.md)
