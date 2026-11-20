# MHI Remote

Full-screen IR remote for Mitsubishi Heavy Industries SRK-CR wall units (SRK52/71/90 CRBN / CRBP).

Four selectable protocols:

| Name | Frame | Source | Notes |
|---|---|---|---|
| ZJ  | 88-bit  | IRremoteESP8266 MITSUBISHI_HEAVY_88 (RKX502A001C) | verified byte-for-byte |
| ZEA | 88-bit  | ToniA arduino-heatpumpir ZEA | different fan / air-flow / 3D codes; verified against ToniA |
| ZMP | 88-bit  | ToniA arduino-heatpumpir ZMP (RKX502A001P) | no left/right flow, no 3D; verified against ToniA |
| ZM  | 152-bit | IRremoteESP8266 MITSUBISHI_HEAVY_152 (RLA502A700B) | verified byte-for-byte |

Every build is signed with the same key (app/mhiremote.jks), so new versions install over old ones.
