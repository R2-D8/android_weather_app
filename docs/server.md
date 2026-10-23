# Home server (`red8`)

Always-on old laptop that hosts the radar backend (Phase 4 of [plan.md](plan.md)) and, later, other personal services. Access: `ssh r2d8@red8` with a key, over Tailscale.

## Current state (cleaned up 2026-10-23)

- **Hardware/OS:** HP laptop, Intel i5-6200U (4 threads), 7.6 GB RAM, 238 GB SSD (LVM root 100 GB, about 135 GB unallocated), Ubuntu Server 24.04.5 LTS (supported to 2029).
- **Network:** Wi-Fi only, on the router's **guest** network `FRITZ! Guest` (intentional). Tailscale IP `100.95.20.125`, key expiry disabled. Ethernet port exists, no cable.
- **Users/sudo:** only `r2d8`. Sudo needs a password; no temporary rules remain. `r2d8` is in the `docker` group (root-equivalent).
- **Firewall (ufw):** active, default deny incoming, only port 22 allowed.
- **Docker:** Docker CE 29.x and compose plugin from Docker's apt repo, log rotation set (`local` driver, 10 MB x 3), compose projects go in `/srv/stacks/<service>/` (`compose.yaml`, data in `./data`).
- **Other:** security updates are automatic (unattended-upgrades, no automatic reboot), lid switch ignored, no snaps. The old Minecraft server, its Telegram bot, both Traccar installs and the users `minecraft`, `trekking`, `storage` were removed.

## Rules (the server is only reachable over Tailscale)

- Never change netplan, the Wi-Fi settings, Tailscale or the SSH setup remotely, and keep a second SSH session open for any risky change. Use a self-revert timer for firewall changes.
- Do **not** enable Tailscale SSH remotely: it takes over port 22 on the Tailscale address (the command itself warns it drops your session) and depends on the tailnet access rules.
- Reboot only when someone at home could check the laptop if it does not come back.
- Docker publishes ports around ufw: publish container ports on `127.0.0.1` or `100.95.20.125`, never on all interfaces.
- Claude has no general sudo. Privileged steps are scripts copied to `/tmp` on the server that the user runs with `sudo bash`, writing a readable `/tmp/*.out` result. Always ask before destructive steps.

## Pending

- **Reboot**: kernel 6.8.0-147 is installed, 6.8.0-139 is running. Check afterwards that Wi-Fi and Tailscale came back.
- Optional hardening (with revert timers): `ufw allow in on tailscale0`, turn off SSH password login (key login works).
- Check/disable Wi-Fi power saving (`iw` is not installed).

## Later

- **Weather radar backend** (Phase 4): Compose stack in `/srv/stacks/weather-radar`.
- **Pi-hole:** needs a fixed LAN IP and the laptop on the **main** network (the guest network isolates it), so an Ethernet cable or the main Wi-Fi. Port 53 conflicts with the `systemd-resolved` stub and must be freed first.
- **Password manager** (Vaultwarden) and **git server** (Forgejo, lighter than GitLab for this hardware). A password manager needs real backups before it is used.
- Backups: none needed today (nothing irreplaceable on the server); decide a target when the first irreplaceable data arrives.
