# MHI Remote

Full-screen IR remote for Mitsubishi Heavy Industries SRK-CR wall units (SRK52/71/90 CRBN / CRBP).

- 88-bit protocol (RKX502A001C family), ported from IRremoteESP8266 and verified byte-for-byte against it.
- Sends the full state on every press: power, mode, temperature, fan, HI POWER / ECONO, air flow, 3D AUTO, CLEAN.
- Every build is signed with the same key (app/mhiremote.jks), so new versions install over old ones.
