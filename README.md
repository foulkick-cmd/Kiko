# MHI Remote

Phone IR replica of the wireless remote for Mitsubishi Heavy Industries SRK-CR wall units
(SRK52/71/90 CRBN / CRBP), layout taken from catalog HB91-15R02E.

- Sends the full state on every key press, like the original remote.
- Two encodings, switchable at the top: 88-bit (RKX502A001C family) and 152-bit (RLA502A700B family),
  ported from IRremoteESP8266 and checked byte-for-byte against it (600 random states + real capture).
- Not in either known protocol: ON/OFF TIMER (both), SLEEP (88-bit).
- Needs a phone with an IR blaster.
