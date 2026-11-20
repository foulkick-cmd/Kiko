# MHI Remote

Full-screen IR remote for Mitsubishi Heavy Industries SRK-CR wall units (SRK52/71/90 CRBN / CRBP).

| Name | Source | Notes |
|---|---|---|
| CR  | ZJ 88-bit frame, left/right = 0 by default | matches a real SRK13CRV remote capture (SmartIR 1680) 105/105; fan forced to auto in DRY like the real remote |
| ZJ  | IRremoteESP8266 MITSUBISHI_HEAVY_88 (RKX502A001C) | verified byte-for-byte |
| ZMP | ToniA arduino-heatpumpir ZMP (RKX502A001P) | no left/right flow, no 3D; verified against ToniA |

Every build is signed with the same key (app/mhiremote.jks), so new versions install over old ones.
